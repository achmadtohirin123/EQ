package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Visualizer
import android.media.audiofx.DynamicsProcessing
import android.media.AudioTrack
import android.media.AudioFormat
import android.media.AudioAttributes
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AudioDatabase
import com.example.data.PresetEntity
import com.example.data.PresetRepository
import com.example.dsp.AudioSignalGenerator
import com.example.dsp.BiquadFilter
import com.example.dsp.CompressorLimiter
import com.example.dsp.StereoWidener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.log10
import kotlin.math.exp

/**
 * Android Foreground Service untuk Mengelola Engine Pemrosesan Audio (DSP) System-Wide.
 * Layanan ini memantau sinyal audio global Android (YouTube, Spotify, dsb)
 * dan menerapkan Equalizer, Bass Boost, Virtualizer, dan Reverb secara real-time.
 */
class AudioProcessorService : Service() {

    // Service Binder
    inner class LocalBinder : Binder() {
        fun getService(): AudioProcessorService = this@AudioProcessorService
    }
    private val binder = LocalBinder()

    // Database & Repository
    private lateinit var repository: PresetRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy { getSharedPreferences("audio_settings_prefs", Context.MODE_PRIVATE) }
    private var lastAppliedPresetId: Int? = null
    private var saveJob: Job? = null

    // Backwards Compatibility / UI Dummies
    val signalGenerator = AudioSignalGenerator() // Tetap dipertahankan agar tidak break UI references
    private val compressorLimiter = CompressorLimiter()
    private val stereoWidener = StereoWidener()

    // Frekuensi standar 31-Band ISO Equalizer Grafis (20Hz s.d. 20kHz)
    val eqFrequencies = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f, 800f,
        1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f
    )
    
    // Master Gains berjalan (dalam desibel, dB)
    private val currentEqGains = FloatArray(31) { 0f }
    var bassBoostDb = 0f
        set(value) {
            field = value
            updateBassBoost()
            saveActivePresetStateToDbDebounced()
        }

    // Blok Module Bypass Pengaturan
    var isEqBlockEnabled = true
        set(value) {
            field = value
            for (group in activeEffects.values) {
                try { group.equalizer?.enabled = value && signalGenerator.isPlaying } catch (e: Exception) {}
            }
            prefs.edit().putBoolean("isEqBlockEnabled", value).apply()
        }

    var isBassBoostBlockEnabled = true
        set(value) {
            field = value
            for (group in activeEffects.values) {
                try { group.bassBoost?.enabled = value && signalGenerator.isPlaying } catch (e: Exception) {}
            }
            prefs.edit().putBoolean("isBassBoostBlockEnabled", value).apply()
        }

    var isVirtualizerBlockEnabled = true
        set(value) {
            field = value
            for (group in activeEffects.values) {
                try { group.virtualizer?.enabled = value && signalGenerator.isPlaying } catch (e: Exception) {}
            }
            prefs.edit().putBoolean("isVirtualizerBlockEnabled", value).apply()
        }

    var isReverbBlockEnabled = true
        set(value) {
            field = value
            for (group in activeEffects.values) {
                try { group.presetReverb?.enabled = value && signalGenerator.isPlaying } catch (e: Exception) {}
            }
            prefs.edit().putBoolean("isReverbBlockEnabled", value).apply()
        }

    // Independent Left and Right volume sliders
    var volumeLeft = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateChannelVolumes()
            prefs.edit().putFloat("volumeLeft", field).apply()
        }

    var volumeRight = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateChannelVolumes()
            prefs.edit().putFloat("volumeRight", field).apply()
        }

    // --- DIGITAL CROSSOVER PRO 4-WAY CONFIGURATION ---
    var crossoverSubLowHz = 80f
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverLowMidHz = 250f
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverMidHighHz = 3500f
        set(value) { field = value; saveActivePresetStateToDbDebounced() }

    var crossoverSubGain = 0f
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverLowGain = 0f
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverMidGain = 0f
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverHighGain = 0f
        set(value) { field = value; saveActivePresetStateToDbDebounced() }

    var crossoverSubMute = false
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverLowMute = false
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverMidMute = false
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverHighMute = false
        set(value) { field = value; saveActivePresetStateToDbDebounced() }

    var crossoverSubSolo = false
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverLowSolo = false
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverMidSolo = false
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverHighSolo = false
        set(value) { field = value; saveActivePresetStateToDbDebounced() }

    var crossoverSubInvert = false
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverLowInvert = false
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverMidInvert = false
        set(value) { field = value; saveActivePresetStateToDbDebounced() }
    var crossoverHighInvert = false
        set(value) { field = value; saveActivePresetStateToDbDebounced() }

    // --- PROFESSIONAL AUDIO LIMITER CONFIGURATION ---
    var limiterThresholdDb = 0f
        set(value) { 
            field = value.coerceIn(-24f, 0f)
            updateLimiterConfig()
            saveActivePresetStateToDbDebounced()
        }
    var limiterReleaseMs = 100f
        set(value) { 
            field = value.coerceIn(10f, 1000f)
            updateLimiterConfig()
            saveActivePresetStateToDbDebounced()
        }
    var limiterCeilingDb = -0.1f
        set(value) { 
            field = value.coerceIn(-3f, 0f)
            saveActivePresetStateToDbDebounced()
        }
    var limiterKneeDb = 0f
        set(value) { 
            field = value.coerceIn(0f, 6f)
            saveActivePresetStateToDbDebounced()
        }
    var isLimiterEnabled = true
        set(value) { 
            field = value
            updateLimiterConfig()
            saveActivePresetStateToDbDebounced()
        }

    fun updateLimiterConfig() {
        for (group in activeEffects.values) {
            group.dynamicsProcessing?.let { applyLimiterToDynamics(it) }
        }
    }

    fun applyLimiterToDynamics(dyn: DynamicsProcessing) {
        try {
            // Android DynamicsProcessing.Limiter:
            // Limiter(inUse: Boolean, enabled: Boolean, linkGroup: Int, attackTime: Float, releaseTime: Float, ratio: Float, threshold: Float, postGain: Float)
            val limiterConfig = DynamicsProcessing.Limiter(
                true, // inUse
                isLimiterEnabled, // enabled
                0, // linkGroup (same group for L/R preservation)
                1.0f, // attackTime (ultra-fast brickwall response)
                limiterReleaseMs, // releaseTime MS
                50.0f, // ratio (brickwall slope limit)
                limiterThresholdDb, // target threshold Db
                0.0f // postGain Db
            )
            dyn.setLimiterByChannelIndex(0, limiterConfig)
            dyn.setLimiterByChannelIndex(1, limiterConfig)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- REALTIME FREQUENCY BAND & GAIN REDUCTION METERING FLOWS ---
    private val _crossoverSubLevel = MutableStateFlow(0f)
    val crossoverSubLevel = _crossoverSubLevel.asStateFlow()

    private val _crossoverLowLevel = MutableStateFlow(0f)
    val crossoverLowLevel = _crossoverLowLevel.asStateFlow()

    private val _crossoverMidLevel = MutableStateFlow(0f)
    val crossoverMidLevel = _crossoverMidLevel.asStateFlow()

    private val _crossoverHighLevel = MutableStateFlow(0f)
    val crossoverHighLevel = _crossoverHighLevel.asStateFlow()

    private val _limiterGainReduction = MutableStateFlow(0f)
    val limiterGainReduction = _limiterGainReduction.asStateFlow()

    private var audioPlaybackJob: Job? = null

    // Live Metering data yang diekspos ke UI Compose pada 60FPS
    private val _vuLeft = MutableStateFlow(0f)
    val vuLeft = _vuLeft.asStateFlow()

    private val _vuRight = MutableStateFlow(0f)
    val vuRight = _vuRight.asStateFlow()

    private val _clipLeft = MutableStateFlow(false)
    val clipLeft = _clipLeft.asStateFlow()

    private val _clipRight = MutableStateFlow(false)
    val clipRight = _clipRight.asStateFlow()

    // Data spektrum amplitudo 31 bar murni untuk spektrum analyzer
    private val _spectrumData = MutableStateFlow(FloatArray(31) { 0f })
    val spectrumData = _spectrumData.asStateFlow()

    // Informasi State saat ini
    private val _activePresetName = MutableStateFlow("Sedang Memuat...")
    val activePresetName = _activePresetName.asStateFlow()

    private val _activePreset = MutableStateFlow<PresetEntity?>(null)
    val activePreset = _activePreset.asStateFlow()

    private val _isEnginePlaying = MutableStateFlow(true)
    val isEnginePlaying = _isEnginePlaying.asStateFlow()

    // Pengelolaan Audio Effects Android secara global / dinamis per-Audio Session
    private val activeEffects = java.util.concurrent.ConcurrentHashMap<Int, AudioEffectsGroup>()
    private var globalVisualizer: Visualizer? = null
    private var isReceiverRegistered = false

    companion object {
        private const val CHANNEL_ID = "bro_eq_channel_audio"
        private const val NOTIFICATION_ID = 1001
        
        var SERVICE_INSTANCE: AudioProcessorService? = null
            private set
    }

    // Broadcast Receiver untuk mendeteksi audio session dari aplikasi lain (YouTube, Spotify, dsb)
    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (sessionId != -1 && sessionId != 0) {
                if (action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                    initEffectsForSession(sessionId)
                } else if (action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION) {
                    releaseEffectsForSession(sessionId)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        SERVICE_INSTANCE = this
        
        // Atur agar signalGenerator dummy dianggap default true
        signalGenerator.isPlaying = true
        _isEnginePlaying.value = true
        syncLocalAudioPlayback()

        // Load SharedPreferences
        isEqBlockEnabled = prefs.getBoolean("isEqBlockEnabled", true)
        isBassBoostBlockEnabled = prefs.getBoolean("isBassBoostBlockEnabled", true)
        isVirtualizerBlockEnabled = prefs.getBoolean("isVirtualizerBlockEnabled", true)
        isReverbBlockEnabled = prefs.getBoolean("isReverbBlockEnabled", true)
        volumeLeft = prefs.getFloat("volumeLeft", 1.0f)
        volumeRight = prefs.getFloat("volumeRight", 1.0f)

        // Inisialisasi Database
        val database = AudioDatabase.getDatabase(this)
        repository = PresetRepository(database.presetDao())

        // Inisialisasi dummy compressor param
        compressorLimiter.init(44100f)

        // Buat Notification Channel untuk Foreground Service
        createNotificationChannel()

        // Daftarkan Broadcast Receiver untuk mengikat audio session dari YouTube / Spotify
        try {
            val filter = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(sessionReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Terapkan efek global pada session 0 (Global Mix) secara default
        initEffectsForSession(0)

        // Mulai visualizer global
        startGlobalVisualizer()

        // Ambil preset aktif awal dari database secara asinkron
        serviceScope.launch {
            repository.ensureDefaultPresetsPopulated()
            repository.allPresets.collect { list ->
                val active = list.firstOrNull { it.isSelected }
                active?.let { 
                    if (it.id != lastAppliedPresetId) {
                        applyPresetSettings(it)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "TOGGLE_PLAYBACK" || action == "TOGGLE_ENGINE") {
            signalGenerator.isPlaying = !signalGenerator.isPlaying
            _isEnginePlaying.value = signalGenerator.isPlaying
            // Terapkan keadaan aktif / nonaktif ke seluruh filter
            for (group in activeEffects.values) {
                group.setEnabled(signalGenerator.isPlaying)
            }
            if (signalGenerator.isPlaying) {
                startGlobalVisualizer()
            } else {
                stopGlobalVisualizer()
                _vuLeft.value = 0f
                _vuRight.value = 0f
                _clipLeft.value = false
                _clipRight.value = false
                _spectrumData.value = FloatArray(31) { 0f }
            }
            syncLocalAudioPlayback()
            updateNotification()
        }
        
        // Mulai foreground service dengan melempar sticky notification
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        SERVICE_INSTANCE = null
        
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(sessionReceiver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isReceiverRegistered = false
        }

        stopGlobalVisualizer()
        stopLocalAudioPlayback()

        // Bebaskan seluruh session effects
        val keys = activeEffects.keys().toList()
        for (k in keys) {
            releaseEffectsForSession(k)
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Menginisialisasi effects group untuk audio session tertentu secara aman.
     */
    @Synchronized
    private fun initEffectsForSession(sessionId: Int) {
        if (activeEffects.containsKey(sessionId)) return

        var eq: Equalizer? = null
        var bass: BassBoost? = null
        var virt: Virtualizer? = null
        var reverb: PresetReverb? = null

        // Inisialisasi Equalizer HP
        try {
            eq = Equalizer(0, sessionId).apply {
                enabled = isEqBlockEnabled && signalGenerator.isPlaying
            }
            applyEqToEqualizer(eq)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Inisialisasi Bass Boost HP
        try {
            bass = BassBoost(0, sessionId).apply {
                enabled = isBassBoostBlockEnabled && signalGenerator.isPlaying
            }
            applyBassToBassBoost(bass)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Inisialisasi Virtualizer (Stereo Widener) HP
        try {
            virt = Virtualizer(0, sessionId).apply {
                enabled = isVirtualizerBlockEnabled && signalGenerator.isPlaying
            }
            applyWidthToVirtualizer(virt)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Inisialisasi Preset Reverb HP
        try {
            reverb = PresetReverb(0, sessionId).apply {
                enabled = isReverbBlockEnabled && signalGenerator.isPlaying
            }
            applyReverbToPresetReverb(reverb)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Inisialisasi DynamicsProcessing untuk Master Volume L/R
        var dyn: DynamicsProcessing? = null
        try {
            val builder = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, // L and R channels
                false, 0, // preEq
                false, 0, // mbc
                false, 0, // postEq
                true      // limiter enabled!
            )
            dyn = DynamicsProcessing(0, sessionId, builder.build()).apply {
                enabled = signalGenerator.isPlaying
            }
            applyVolumeToDynamics(dyn)
            applyLimiterToDynamics(dyn)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        activeEffects[sessionId] = AudioEffectsGroup(sessionId, eq, bass, virt, reverb, dyn)
    }

    /**
     * Melepas effects group dari audio session tertentu.
     */
    @Synchronized
    private fun releaseEffectsForSession(sessionId: Int) {
        activeEffects.remove(sessionId)?.release()
    }

    /**
     * Memulai visualizer global di session 0 untuk menangkap semua sound spektrum perangkat.
     */
    fun startGlobalVisualizer() {
        if (globalVisualizer != null) return
        try {
            globalVisualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        if (waveform != null && signalGenerator.isPlaying) {
                            var sum = 0f
                            var maxVal = 0f
                            for (b in waveform) {
                                val v = (b.toInt() and 0xFF) - 128
                                val normal = v / 128f
                                sum += normal * normal
                                if (abs(normal) > maxVal) maxVal = abs(normal)
                            }
                            val rms = sqrt(sum / waveform.size)
                            _vuLeft.value = (rms * 2.2f * volumeLeft).coerceIn(0f, 1f)
                            _vuRight.value = (rms * 2.2f * volumeRight).coerceIn(0f, 1f)
                            _clipLeft.value = (maxVal >= 0.98f) && (volumeLeft > 0.05f)
                            _clipRight.value = (maxVal >= 0.98f) && (volumeRight > 0.05f)
                        } else {
                            _vuLeft.value = 0f
                            _vuRight.value = 0f
                            _clipLeft.value = false
                            _clipRight.value = false
                        }
                    }

                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft != null && signalGenerator.isPlaying) {
                            val rawSampling = samplingRate.toFloat()
                            val samplingRateHz = if (rawSampling > 1000000f) rawSampling / 1000f else rawSampling
                            val n = fft.size
                            val magnitudes = FloatArray(n / 2 + 1)
                            magnitudes[0] = abs(fft[0].toFloat())
                            magnitudes[n / 2] = abs(fft[1].toFloat())
                            for (i in 1 until n / 2) {
                                val r = fft[2 * i].toFloat()
                                val im = fft[2 * i + 1].toFloat()
                                magnitudes[i] = sqrt(r * r + im * im)
                            }

                            val newSpectrum = FloatArray(31)
                            val prevSpectrum = _spectrumData.value
                            val maxVolFactor = maxOf(volumeLeft, volumeRight)

                            for (vBand in 0 until 31) {
                                val centerFreq = eqFrequencies[vBand]
                                
                                // Dynamic center bin selection
                                val centerBin = (centerFreq * n / samplingRateHz).toInt().coerceIn(1, n / 2 - 1)
                                
                                // Average adjacent bins to smooth noise and gain an organic musical flow
                                val leftBin = (centerBin - 1).coerceAtLeast(1)
                                val rightBin = (centerBin + 1).coerceAtMost(n / 2 - 1)
                                val energy = (magnitudes[leftBin] + magnitudes[centerBin] + magnitudes[rightBin]) / 3f
                                
                                // Convert to decibel volume scale (dB) for maximum reality matching human ear
                                val dbOffset = 20.0 * log10((energy + 1e-4) / 128.0)
                                // Standard DAW DB scale ranges from -38 dB (quiet) to 0 dB (loud). Map that to 0.01..1.0
                                val targetFraction = ((dbOffset + 38.0) / 38.0).toFloat().coerceIn(0.01f, 1.0f)

                                // Dynamic weight compensation for higher bands (since physics naturally dampens high frequency energies)
                                val freqWeight = 1.0f + (vBand.toFloat() * 0.035f)
                                val weightedTarget = targetFraction * freqWeight * maxVolFactor

                                val previousVal = if (vBand < prevSpectrum.size) prevSpectrum[vBand] else 0f
                                val smoothed = if (weightedTarget > previousVal) {
                                    // High response rise
                                    (previousVal * 0.12f + weightedTarget * 0.88f)
                                        .coerceIn(0f, 1f)
                                } else {
                                    // Smooth analog visual decay
                                    (previousVal * 0.81f + weightedTarget * 0.19f)
                                        .coerceIn(0f, 1f)
                                }
                                newSpectrum[vBand] = smoothed
                            }
                            _spectrumData.value = newSpectrum

                            // --- REALTIME 4-WAY CROSSOVER ENERGY SPLITTING ---
                            var subSum = 0f
                            var lowSum = 0f
                            var midSum = 0f
                            var highSum = 0f
                            
                            var subCount = 0
                            var lowCount = 0
                            var midCount = 0
                            var highCount = 0
                            
                            val nBins = n / 2
                            for (i in 1 until nBins) {
                                val f = i.toFloat() * samplingRateHz / n.toFloat()
                                val mag = magnitudes[i]
                                
                                if (f < crossoverSubLowHz) {
                                    subSum += mag
                                    subCount++
                                } else if (f < crossoverLowMidHz) {
                                    lowSum += mag
                                    lowCount++
                                } else if (f < crossoverMidHighHz) {
                                    midSum += mag
                                    midCount++
                                } else {
                                    highSum += mag
                                    highCount++
                                }
                            }
                            
                            val anySolo = crossoverSubSolo || crossoverLowSolo || crossoverMidSolo || crossoverHighSolo
                            
                            fun getCrossoverBandLevel(avgMag: Float, gainDb: Float, isMuted: Boolean, isSoloSelected: Boolean): Float {
                                if (isMuted) return 0f
                                if (anySolo && !isSoloSelected) return 0f
                                
                                val dbOffset = 20.0 * kotlin.math.log10((avgMag + 1e-4) / 128.0)
                                val totalDb = dbOffset + gainDb
                                val level = ((totalDb + 38.0) / 38.0).toFloat().coerceIn(0f, 1f)
                                return level
                            }
                            
                            val subAvg = if (subCount > 0) subSum / subCount else 0f
                            val lowAvg = if (lowCount > 0) lowSum / lowCount else 0f
                            val midAvg = if (midCount > 0) midSum / midCount else 0f
                            val highAvg = if (highCount > 0) highSum / highCount else 0f
                            
                            val adjustedHighAvg = highAvg * 2.2f
                            
                            val rawSub = getCrossoverBandLevel(subAvg, crossoverSubGain, crossoverSubMute, crossoverSubSolo) * 1.5f * maxVolFactor
                            val rawLow = getCrossoverBandLevel(lowAvg, crossoverLowGain, crossoverLowMute, crossoverLowSolo) * 1.3f * maxVolFactor
                            val rawMid = getCrossoverBandLevel(midAvg, crossoverMidGain, crossoverMidMute, crossoverMidSolo) * 1.1f * maxVolFactor
                            val rawHigh = getCrossoverBandLevel(adjustedHighAvg, crossoverHighGain, crossoverHighMute, crossoverHighSolo) * 1.2f * maxVolFactor
                            
                            _crossoverSubLevel.value = (_crossoverSubLevel.value * 0.15f + rawSub.coerceIn(0f, 1f) * 0.85f)
                            _crossoverLowLevel.value = (_crossoverLowLevel.value * 0.15f + rawLow.coerceIn(0f, 1f) * 0.85f)
                            _crossoverMidLevel.value = (_crossoverMidLevel.value * 0.15f + rawMid.coerceIn(0f, 1f) * 0.85f)
                            _crossoverHighLevel.value = (_crossoverHighLevel.value * 0.15f + rawHigh.coerceIn(0f, 1f) * 0.85f)

                            // --- PROFESSIONAL MASTER LIMITER DYNAMIC DISPLAY ---
                            val peakVolume = maxOf(_vuLeft.value, _vuRight.value)
                            val peakDb = if (peakVolume <= 0.005f) -120f else 20f * kotlin.math.log10(peakVolume)
                            
                            val targetGR = if (isLimiterEnabled && peakDb > limiterThresholdDb) {
                                peakDb - limiterThresholdDb
                            } else {
                                0f
                            }.coerceIn(0f, 24f)
                            
                            val grSmoother = (1.0f - (10.0f / limiterReleaseMs.coerceAtLeast(10f))).coerceIn(0.5f, 0.95f)
                            _limiterGainReduction.value = (_limiterGainReduction.value * grSmoother + targetGR * (1.0f - grSmoother))
                        } else {
                            _spectrumData.value = FloatArray(31) { 0f }
                            _crossoverSubLevel.value = 0f
                            _crossoverLowLevel.value = 0f
                            _crossoverMidLevel.value = 0f
                            _crossoverHighLevel.value = 0f
                            _limiterGainReduction.value = 0f
                        }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, true, true)
                enabled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopGlobalVisualizer() {
        try {
            globalVisualizer?.enabled = false
            globalVisualizer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        globalVisualizer = null
    }

    /**
     * Memicu pembacaan izin visualizer ulang sewaktu aplikasi di-resume
     */
    fun checkAndStartVisualizer() {
        if (signalGenerator.isPlaying) {
            startGlobalVisualizer()
        }
    }

    // Fungsi pemetaan setelan slider EQ ke Equalizer bawaan Android berbasis Gaussian Weighted Octave Difference
    private fun applyEqToEqualizer(equalizer: Equalizer) {
        try {
            val numBands = equalizer.numberOfBands.toInt()
            val range = equalizer.bandLevelRange
            val minLevel = range[0]
            val maxLevel = range[1]

            for (band in 0 until numBands) {
                val centerFreqHz = equalizer.getCenterFreq(band.toShort()) / 1000f

                var weightedSum = 0f
                var totalWeight = 0f

                for (i in 0 until 31) {
                    val sliderFreq = eqFrequencies[i]
                    // Octave logarithmic distance
                    val octaveDiff = abs(log10(sliderFreq / centerFreqHz))
                    // Gaussian curve with standard band deviation (0.45 octave) to weight neighboring bands
                    val weight = exp(-(octaveDiff * octaveDiff) / (2f * 0.45f * 0.45f))

                    if (weight > 0.01f) {
                        weightedSum += currentEqGains[i] * weight
                        totalWeight += weight
                    }
                }

                val targetGainDb = if (totalWeight > 0f) weightedSum / totalWeight else 0f
                val levelMb = (targetGainDb * 100).toInt().coerceIn(minLevel.toInt(), maxLevel.toInt())
                equalizer.setBandLevel(band.toShort(), levelMb.toShort())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyBassToBassBoost(bassBoost: BassBoost) {
        try {
            if (bassBoost.strengthSupported) {
                val strengthValue = (bassBoostDb / 15f * 1000f).toInt().coerceIn(0, 1000)
                bassBoost.setStrength(strengthValue.toShort())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyWidthToVirtualizer(virtualizer: Virtualizer) {
        try {
            if (virtualizer.strengthSupported) {
                val widthValue = (stereoWidener.stereoWidth / 2f * 1000f).toInt().coerceIn(0, 1000)
                virtualizer.setStrength(widthValue.toShort())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyReverbToPresetReverb(presetReverb: PresetReverb) {
        try {
            val preset = when {
                stereoWidener.reverbLevel <= 0.05f -> PresetReverb.PRESET_NONE
                stereoWidener.reverbLevel <= 0.2f -> PresetReverb.PRESET_SMALLROOM
                stereoWidener.reverbLevel <= 0.4f -> PresetReverb.PRESET_MEDIUMROOM
                stereoWidener.reverbLevel <= 0.6f -> PresetReverb.PRESET_LARGEROOM
                stereoWidener.reverbLevel <= 0.8f -> PresetReverb.PRESET_MEDIUMHALL
                else -> PresetReverb.PRESET_LARGEHALL
            }
            presetReverb.preset = preset
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun updateFiltersConfig() {
        for (group in activeEffects.values) {
            group.equalizer?.let { applyEqToEqualizer(it) }
        }
    }

    private fun updateBassBoost() {
        for (group in activeEffects.values) {
            group.bassBoost?.let { applyBassToBassBoost(it) }
        }
    }

    /**
     * Menerapkan koefisien setelan lengkap dari objek PresetEntity.
     */
    fun applyPresetSettings(preset: PresetEntity) {
        lastAppliedPresetId = preset.id
        _activePresetName.value = preset.name
        _activePreset.value = preset
        
        val gainsList = preset.getGainsList()
        for (i in 0 until 31) {
            if (i < gainsList.size) {
                currentEqGains[i] = gainsList[i]
            }
        }

        // Terapkan Compressor Dummies
        compressorLimiter.thresholdDb = preset.compThresholdDb
        compressorLimiter.ratio = preset.compRatio
        compressorLimiter.attackMs = preset.compAttackMs
        compressorLimiter.releaseMs = preset.compReleaseMs
        compressorLimiter.makeupGainDb = preset.compMakeupGainDb
        
        // Terapkan Widener & Bass & Reverb
        stereoWidener.stereoWidth = preset.stereoWidth
        stereoWidener.reverbLevel = preset.reverbLevel
        bassBoostDb = preset.bassBoostDb // Ini otomatis memicu updateBassBoost()

        // Terapkan Crossover Parameters
        crossoverSubLowHz = preset.crossoverSubLowHz
        crossoverLowMidHz = preset.crossoverLowMidHz
        crossoverMidHighHz = preset.crossoverMidHighHz
        
        crossoverSubGain = preset.crossoverSubGain
        crossoverLowGain = preset.crossoverLowGain
        crossoverMidGain = preset.crossoverMidGain
        crossoverHighGain = preset.crossoverHighGain
        
        crossoverSubMute = preset.crossoverSubMute
        crossoverLowMute = preset.crossoverLowMute
        crossoverMidMute = preset.crossoverMidMute
        crossoverHighMute = preset.crossoverHighMute
        
        crossoverSubSolo = preset.crossoverSubSolo
        crossoverLowSolo = preset.crossoverLowSolo
        crossoverMidSolo = preset.crossoverMidSolo
        crossoverHighSolo = preset.crossoverHighSolo
        
        crossoverSubInvert = preset.crossoverSubInvert
        crossoverLowInvert = preset.crossoverLowInvert
        crossoverMidInvert = preset.crossoverMidInvert
        crossoverHighInvert = preset.crossoverHighInvert

        // Terapkan Limiter Parameters
        limiterThresholdDb = preset.limiterThresholdDb
        limiterReleaseMs = preset.limiterReleaseMs
        limiterCeilingDb = preset.limiterCeilingDb
        limiterKneeDb = preset.limiterKneeDb
        isLimiterEnabled = preset.isLimiterEnabled

        // Pancing update filter equalizer
        updateFiltersConfig()
 
        // Perbarui semua live active effects kustom HP
        for (group in activeEffects.values) {
            group.equalizer?.let { applyEqToEqualizer(it) }
            group.bassBoost?.let { applyBassToBassBoost(it) }
            group.virtualizer?.let { applyWidthToVirtualizer(it) }
            group.presetReverb?.let { applyReverbToPresetReverb(it) }
            group.dynamicsProcessing?.let { applyLimiterToDynamics(it) }
        }
 
        updateNotification()
    }
 
    /**
     * Menyimpan setelan Equalizer kustom baru ke dalam database lokal.
     */
    fun saveCustomPreset(name: String) {
        serviceScope.launch {
            val gainsList = currentEqGains.toList()
            repository.saveCustomPreset(
                name = name,
                gains = gainsList,
                thresholdDb = compressorLimiter.thresholdDb,
                ratio = compressorLimiter.ratio,
                attack = compressorLimiter.attackMs,
                release = compressorLimiter.releaseMs,
                makeup = compressorLimiter.makeupGainDb,
                width = stereoWidener.stereoWidth,
                bass = bassBoostDb,
                reverb = stereoWidener.reverbLevel,
                
                crossoverSubLowHz = crossoverSubLowHz,
                crossoverLowMidHz = crossoverLowMidHz,
                crossoverMidHighHz = crossoverMidHighHz,
                
                crossoverSubGain = crossoverSubGain,
                crossoverLowGain = crossoverLowGain,
                crossoverMidGain = crossoverMidGain,
                crossoverHighGain = crossoverHighGain,
                
                crossoverSubMute = crossoverSubMute,
                crossoverLowMute = crossoverLowMute,
                crossoverMidMute = crossoverMidMute,
                crossoverHighMute = crossoverHighMute,
                
                crossoverSubSolo = crossoverSubSolo,
                crossoverLowSolo = crossoverLowSolo,
                crossoverMidSolo = crossoverMidSolo,
                crossoverHighSolo = crossoverHighSolo,
                
                crossoverSubInvert = crossoverSubInvert,
                crossoverLowInvert = crossoverLowInvert,
                crossoverMidInvert = crossoverMidInvert,
                crossoverHighInvert = crossoverHighInvert,
                
                limiterThresholdDb = limiterThresholdDb,
                limiterReleaseMs = limiterReleaseMs,
                limiterCeilingDb = limiterCeilingDb,
                limiterKneeDb = limiterKneeDb,
                isLimiterEnabled = isLimiterEnabled
            )
        }
    }
 
    fun saveActivePresetStateToDbDebounced() {
        saveJob?.cancel()
        saveJob = serviceScope.launch(Dispatchers.IO) {
            delay(500)
            val currentPreset = _activePreset.value ?: return@launch
            val updatedPreset = currentPreset.copy(
                eqGainsString = PresetEntity.convertGainsToString(currentEqGains.toList()),
                compThresholdDb = compressorLimiter.thresholdDb,
                compRatio = compressorLimiter.ratio,
                compAttackMs = compressorLimiter.attackMs,
                compReleaseMs = compressorLimiter.releaseMs,
                compMakeupGainDb = compressorLimiter.makeupGainDb,
                stereoWidth = stereoWidener.stereoWidth,
                bassBoostDb = bassBoostDb,
                reverbLevel = stereoWidener.reverbLevel,
                
                crossoverSubLowHz = crossoverSubLowHz,
                crossoverLowMidHz = crossoverLowMidHz,
                crossoverMidHighHz = crossoverMidHighHz,
                
                crossoverSubGain = crossoverSubGain,
                crossoverLowGain = crossoverLowGain,
                crossoverMidGain = crossoverMidGain,
                crossoverHighGain = crossoverHighGain,
                
                crossoverSubMute = crossoverSubMute,
                crossoverLowMute = crossoverLowMute,
                crossoverMidMute = crossoverMidMute,
                crossoverHighMute = crossoverHighMute,
                
                crossoverSubSolo = crossoverSubSolo,
                crossoverLowSolo = crossoverLowSolo,
                crossoverMidSolo = crossoverMidSolo,
                crossoverHighSolo = crossoverHighSolo,
                
                crossoverSubInvert = crossoverSubInvert,
                crossoverLowInvert = crossoverLowInvert,
                crossoverMidInvert = crossoverMidInvert,
                crossoverHighInvert = crossoverHighInvert,
                
                limiterThresholdDb = limiterThresholdDb,
                limiterReleaseMs = limiterReleaseMs,
                limiterCeilingDb = limiterCeilingDb,
                limiterKneeDb = limiterKneeDb,
                isLimiterEnabled = isLimiterEnabled
            )
            repository.updatePresetSelection(updatedPreset)
            _activePreset.value = updatedPreset
        }
    }

    /**
     * Memodifikasi satu slider EQ band secara dinamis dari UI Compose.
     */
    fun updateSingleEqBand(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until 31) {
            currentEqGains[bandIndex] = gainDb
            updateFiltersConfig()
            saveActivePresetStateToDbDebounced()
        }
    }

    fun getEqGains(): FloatArray {
        return currentEqGains.clone()
    }

    fun floatToDb(vol: Float): Float {
        if (vol <= 0.005f) return -120f // completely silent
        return 20f * kotlin.math.log10(vol)
    }

    fun updateChannelVolumes() {
        val gainDbLeft = floatToDb(volumeLeft)
        val gainDbRight = floatToDb(volumeRight)
        for (group in activeEffects.values) {
            group.dynamicsProcessing?.let { dyn ->
                try {
                    dyn.setInputGainbyChannel(0, gainDbLeft)
                    dyn.setInputGainbyChannel(1, gainDbRight)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun applyVolumeToDynamics(dyn: DynamicsProcessing) {
        try {
            dyn.setInputGainbyChannel(0, floatToDb(volumeLeft))
            dyn.setInputGainbyChannel(1, floatToDb(volumeRight))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Setters pelorot langsung dari slider UI
    fun updateCompressorThreshold(value: Float) {
        compressorLimiter.thresholdDb = value
        saveActivePresetStateToDbDebounced()
    }

    fun updateCompressorRatio(value: Float) {
        compressorLimiter.ratio = value
        saveActivePresetStateToDbDebounced()
    }

    fun updateCompressorAttack(value: Float) {
        compressorLimiter.attackMs = value
        saveActivePresetStateToDbDebounced()
    }

    fun updateCompressorRelease(value: Float) {
        compressorLimiter.releaseMs = value
        saveActivePresetStateToDbDebounced()
    }

    fun updateCompressorMakeup(value: Float) {
        compressorLimiter.makeupGainDb = value
        saveActivePresetStateToDbDebounced()
    }

    fun updateStereoWidth(value: Float) {
        stereoWidener.stereoWidth = value
        for (group in activeEffects.values) {
            group.virtualizer?.let { applyWidthToVirtualizer(it) }
        }
        saveActivePresetStateToDbDebounced()
    }

    fun updateReverbLevel(value: Float) {
        stereoWidener.reverbLevel = value
        for (group in activeEffects.values) {
            group.presetReverb?.let { applyReverbToPresetReverb(it) }
        }
        saveActivePresetStateToDbDebounced()
    }

    fun getCompressorThreshold() = compressorLimiter.thresholdDb
    fun getCompressorRatio() = compressorLimiter.ratio
    fun getCompressorAttack() = compressorLimiter.attackMs
    fun getCompressorRelease() = compressorLimiter.releaseMs
    fun getCompressorMakeup() = compressorLimiter.makeupGainDb
    fun getStereoWidth() = stereoWidener.stereoWidth
    fun getReverbLevel() = stereoWidener.reverbLevel

    /**
     * Memperbarui UI detail di bar notifikasi media style Android.
     */
    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildServiceNotification())
    }

    private fun buildServiceNotification(): Notification {
        val togglePlayIntent = Intent(this, AudioProcessorService::class.java).apply {
            action = "TOGGLE_PLAYBACK"
        }
        val pTogglePlay = PendingIntent.getService(
            this, 12, togglePlayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val pMain = PendingIntent.getActivity(
            this, 13, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPlaying = signalGenerator.isPlaying
        val playIconChar = if (isPlaying) "⏸" else "▶"
        val statusText = if (isPlaying) "Equalizer Aktif - Presets: ${_activePresetName.value}" else "Equalizer Standby (Mati)"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BRO EQ JOSSS 🔊")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pMain)
            .setOngoing(true)
            .setShowWhen(false)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle())
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_play,
                    playIconChar,
                    pTogglePlay
                )
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Layanan Pemrosesan Audio BRO EQ",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Saluran notifikasi untuk DSP Equalizer global HP yang terus berjalan stabil di latar belakang."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun syncLocalAudioPlayback() {
        if (signalGenerator.isPlaying) {
            startLocalAudioPlayback()
        } else {
            stopLocalAudioPlayback()
        }
    }

    private fun startLocalAudioPlayback() {
        // Dinonaktifkan total agar input audio diproses murni dari system/YouTube/Spotify saja
    }

    private fun stopLocalAudioPlayback() {
        audioPlaybackJob?.cancel()
        audioPlaybackJob = null
    }

    // Kelas pembungkus untuk menampung seluruh efek aktif audio session
    class AudioEffectsGroup(
        val sessionId: Int,
        val equalizer: Equalizer?,
        val bassBoost: BassBoost?,
        val virtualizer: Virtualizer?,
        val presetReverb: PresetReverb?,
        val dynamicsProcessing: DynamicsProcessing?
    ) {
        fun setEnabled(enabled: Boolean) {
            try { equalizer?.enabled = enabled } catch (e: Exception) {}
            try { bassBoost?.enabled = enabled } catch (e: Exception) {}
            try { virtualizer?.enabled = enabled } catch (e: Exception) {}
            try { presetReverb?.enabled = enabled } catch (e: Exception) {}
            try { dynamicsProcessing?.enabled = enabled } catch (e: Exception) {}
        }

        fun release() {
            try { equalizer?.release() } catch (e: Exception) {}
            try { bassBoost?.release() } catch (e: Exception) {}
            try { virtualizer?.release() } catch (e: Exception) {}
            try { presetReverb?.release() } catch (e: Exception) {}
            try { dynamicsProcessing?.release() } catch (e: Exception) {}
        }
    }
}
