package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Android Foreground Service Kelas Profesional untuk Mengelola Engine Pemrosesan Audio (DSP)
 * agar terus berjalan di latar belakang secara stabil tanpa dibunuh oleh sistem Android.
 *
 * Menggunakan thread audio lari-tinggi independen untuk menghindari timbulnya jank/stutter
 * dan mengonsumsi memori minimal.
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

    // Engine DSP Audio
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    @Volatile private var isRunning = false

    // Modul DSP (Bebas Alokasi Memori di Loop Inti)
    val signalGenerator = AudioSignalGenerator()
    private val compressorLimiter = CompressorLimiter()
    private val stereoWidener = StereoWidener()
    
    // 31 Biquad Filters untuk Graphic EQ
    private val filters = Array(31) { BiquadFilter() }

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
            updateFiltersConfig()
        }

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

    companion object {
        private const val CHANNEL_ID = "bro_eq_channel_audio"
        private const val NOTIFICATION_ID = 1001
        
        // Membuka akses statis mandiri yang aman agar UI bisa memantau
        var SERVICE_INSTANCE: AudioProcessorService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        SERVICE_INSTANCE = this
        
        // Inisialisasi Database
        val database = AudioDatabase.getDatabase(this)
        repository = PresetRepository(database.presetDao())

        // Inisialisasi param kompresor
        compressorLimiter.init(44100f)

        // Konfigurasi awal 31 filters
        updateFiltersConfig()

        // Buat Notification Channel untuk Foreground Service
        createNotificationChannel()

        // Jalankan audio loop di thread lari cepat khusus (Low-Latency)
        startAudioProcessing()

        // Ambil preset aktif awal dari database secara asinkron
        serviceScope.launch {
            repository.ensureDefaultPresetsPopulated()
            repository.allPresets.collect { list ->
                val active = list.firstOrNull { it.isSelected }
                active?.let { applyPresetSettings(it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "TOGGLE_PLAYBACK") {
            signalGenerator.isPlaying = !signalGenerator.isPlaying
            updateNotification()
        }
        
        // Mulai foreground service dengan melempar sticky notification
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ mewajibkan pendefinisian type MEDIA_PLAYBACK untuk audio player background
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
        stopAudioProcessing()
        serviceScope.cancel()
        SERVICE_INSTANCE = null
        super.onDestroy()
    }

    /**
     * Memperbarui konfigurasi parameter semua filter Biquad EQ (31 Band + Subharmonic Bass).
     */
    @Synchronized
    fun updateFiltersConfig() {
        for (i in 0 until 31) {
            val freq = eqFrequencies[i]
            val originalGain = currentEqGains[i]
            
            // Subharmonic Bass Booster: Memberikan dorongan ekstra pada 3 frekuensi bass terbawah berjalan (20Hz s.d. 80Hz)
            val bassBoostBonus = if (i <= 6) {
                // Skala bonus meruncing dari 20Hz ke 80Hz
                val weight = (7f - i) / 7f
                bassBoostDb * weight
            } else 0f

            val netGainDb = originalGain + bassBoostBonus
            // Q = 1.41 (sekitar 1 oktaf lebar band demi visual equalizer grafis yang rapi)
            filters[i].configurePeakingEQ(
                centerFreq = freq,
                bandwidthQ = 1.41f,
                gainDb = netGainDb,
                sampleRate = 44100f
            )
        }
    }

    /**
     * Menerapkan koefisien setelan lengkap dari objek PresetEntity.
     */
    fun applyPresetSettings(preset: PresetEntity) {
        _activePresetName.value = preset.name
        _activePreset.value = preset
        
        val gainsList = preset.getGainsList()
        for (i in 0 until 31) {
            if (i < gainsList.size) {
                currentEqGains[i] = gainsList[i]
            }
        }

        // Terapkan Compressor
        compressorLimiter.thresholdDb = preset.compThresholdDb
        compressorLimiter.ratio = preset.compRatio
        compressorLimiter.attackMs = preset.compAttackMs
        compressorLimiter.releaseMs = preset.compReleaseMs
        compressorLimiter.makeupGainDb = preset.compMakeupGainDb
        compressorLimiter.recalculateCoefficients()

        // Terapkan Widener & Bass & Reverb
        stereoWidener.stereoWidth = preset.stereoWidth
        stereoWidener.reverbLevel = preset.reverbLevel
        bassBoostDb = preset.bassBoostDb // Ini otomatis memicu updateFiltersConfig()

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
                reverb = stereoWidener.reverbLevel
            )
        }
    }

    /**
     * Memodifikasi satu slider EQ band secara dinamis dari UI Compose.
     */
    fun updateSingleEqBand(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until 31) {
            currentEqGains[bandIndex] = gainDb
            updateFiltersConfig()
        }
    }

    fun getEqGains(): FloatArray {
        return currentEqGains.clone()
    }

    // Setters untuk parameter kompresor dan gema langsung dari slider UI
    fun updateCompressorThreshold(value: Float) {
        compressorLimiter.thresholdDb = value
        compressorLimiter.recalculateCoefficients()
    }

    fun updateCompressorRatio(value: Float) {
        compressorLimiter.ratio = value
    }

    fun updateCompressorAttack(value: Float) {
        compressorLimiter.attackMs = value
        compressorLimiter.recalculateCoefficients()
    }

    fun updateCompressorRelease(value: Float) {
        compressorLimiter.releaseMs = value
        compressorLimiter.recalculateCoefficients()
    }

    fun updateCompressorMakeup(value: Float) {
        compressorLimiter.makeupGainDb = value
    }

    fun updateStereoWidth(value: Float) {
        stereoWidener.stereoWidth = value
    }

    fun updateReverbLevel(value: Float) {
        stereoWidener.reverbLevel = value
    }

    fun getCompressorThreshold() = compressorLimiter.thresholdDb
    fun getCompressorRatio() = compressorLimiter.ratio
    fun getCompressorAttack() = compressorLimiter.attackMs
    fun getCompressorRelease() = compressorLimiter.releaseMs
    fun getCompressorMakeup() = compressorLimiter.makeupGainDb
    fun getStereoWidth() = stereoWidener.stereoWidth
    fun getReverbLevel() = stereoWidener.reverbLevel

    /**
     * Memulai Audio Engine Loop dengan prioritas thread real-time.
     */
    private fun startAudioProcessing() {
        if (isRunning) return
        isRunning = true

        // Konfigurasi Buffer Size untuk AudioTrack (Menjaga latensi sekecil mungkin)
        val sampleRate = 44100
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        
        // Ukuran ring buffer internal AudioTrack diperbesar dikit untuk cegah underflow
        val bufferSize = max(minBufferSize, 4096 * 4)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback backward if builder fails on old platform
            @Suppress("DEPRECATION")
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        }

        audioTrack?.play()

        // Loop Thread audio inti
        audioThread = thread(start = true, name = "AudioEngineThread", priority = Thread.MAX_PRIORITY) {
            val frameSize = 512 // Menulis audio dalam potongan/blok 512 sampel stereo (1024 floats)
            val bufferToWrite = FloatArray(frameSize * 2)
            
            // Lokasi penampung DSP sementara (bebas alokasi GC)
            val generatorPair = FloatArray(2)
            val dspPair = FloatArray(2)
            
            // Variabel analisis level puncak VU
            var peakLAccum = 0f
            var peakRAccum = 0f
            var rmsSquareSumL = 0f
            var rmsSquareSumR = 0f
            var accumulatedSampleCount = 0

            // Penampung visualizer spectrum (smoothing filter)
            val instantBandEnvelopes = FloatArray(31) { 0f }
            val smoothingDown = 0.90f // Kecepatan peluruhan bar spectrum grafik (adem melandai)
            val smoothingUp = 0.40f   // Responsifitas lonjakan bar spectrum

            while (isRunning) {
                if (!signalGenerator.isPlaying) {
                    // Jika diam, tulis sampel nol agar tidak makan baterai, dan istirahat sejenak
                    bufferToWrite.fill(0f)
                    audioTrack?.write(bufferToWrite, 0, bufferToWrite.size, AudioTrack.WRITE_BLOCKING)
                    
                    // Reset meter visualizer
                    _vuLeft.value = 0f
                    _vuRight.value = 0f
                    _clipLeft.value = false
                    _clipRight.value = false
                    _spectrumData.value = FloatArray(31) { 0f }
                    
                    Thread.sleep(30)
                    continue
                }

                // Loop pemrosesan blok audio sampel per sampel
                for (frame in 0 until frameSize) {
                    // 1. Ambil sampel melodi meluncur dari Synthesizer
                    signalGenerator.nextSample(generatorPair)
                    var currentL = generatorPair[0]
                    var currentR = generatorPair[1]

                    // 2. Cascade Biquad Equalizer filters (Satu demi satu dari 31 band)
                    for (b in 0 until 31) {
                        currentL = filters[b].processLeft(currentL)
                        currentR = filters[b].processRight(currentR)

                        // Ambil tingkat energi band tersebut untuk spektrum grafik 60FPS
                        val energy = (abs(currentL) + abs(currentR)) * 0.5f
                        instantBandEnvelopes[b] = if (energy > instantBandEnvelopes[b]) {
                            instantBandEnvelopes[b] + smoothingUp * (energy - instantBandEnvelopes[b])
                        } else {
                            instantBandEnvelopes[b] * smoothingDown
                        }
                    }

                    // 3. Spasial 3D Reverb & Stereo Widener
                    stereoWidener.process(currentL, currentR, dspPair)
                    currentL = dspPair[0]
                    currentR = dspPair[1]

                    // 4. Master Dynamic Compressor & Limiter (Batas Aman 0dBFS)
                    compressorLimiter.process(currentL, currentR, dspPair)
                    currentL = dspPair[0]
                    currentR = dspPair[1]

                    // Tulis hasil akhir PCM FLOAT ke buffer AudioTrack
                    bufferToWrite[frame * 2] = currentL
                    bufferToWrite[frame * 2 + 1] = currentR

                    // Akumulasikan level metering untuk meter VU L/R
                    peakLAccum = max(peakLAccum, abs(currentL))
                    peakRAccum = max(peakRAccum, abs(currentR))
                    rmsSquareSumL += currentL * currentL
                    rmsSquareSumR += currentR * currentR
                    accumulatedSampleCount++
                }

                // Kirim audio hasil olahan ke speaker perangkat
                audioTrack?.write(bufferToWrite, 0, bufferToWrite.size, AudioTrack.WRITE_BLOCKING)

                // 5. Update level visualizer VU & Spectrum ke UI Flow (dilakukan berkala per-blok)
                if (accumulatedSampleCount > 0) {
                    val floatRmsL = sqrt(rmsSquareSumL / accumulatedSampleCount)
                    val floatRmsR = sqrt(rmsSquareSumR / accumulatedSampleCount)

                    // Kirim Envelope Level RMS termodifikasi ke flow visual
                    _vuLeft.value = floatRmsL * 1.5f
                    _vuRight.value = floatRmsR * 1.5f
                    
                    // Deteksi klip jika mendekati limit absolut 1.0f (0dB)
                    _clipLeft.value = peakLAccum >= 0.98f
                    _clipRight.value = peakRAccum >= 0.98f

                    // Reset akumulator metering
                    peakLAccum = 0f
                    peakRAccum = 0f
                    rmsSquareSumL = 0f
                    rmsSquareSumR = 0f
                    accumulatedSampleCount = 0

                    // Kirim spektrum analyzer 31 bar yang selaras
                    val currentSpectrum = _spectrumData.value.clone()
                    for (b in 0 until 31) {
                        // Perbesar dampak visual agar grafik spektrum neon tampak mantap melompat-lompat
                        currentSpectrum[b] = instantBandEnvelopes[b] * 4.0f
                    }
                    _spectrumData.value = currentSpectrum
                }
            }
        }
    }

    private fun stopAudioProcessing() {
        isRunning = false
        try {
            audioThread?.join(500)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioThread = null

        audioTrack?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        audioTrack = null
    }

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

        val playIconChar = if (signalGenerator.isPlaying) "⏸" else "▶"
        val statusText = if (signalGenerator.isPlaying) "Aktif - Presets: ${_activePresetName.value}" else "Berhenti sementara"

        // Buat Style Media Notification
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
                description = "Saluran notifikasi untuk DSP Equalizer & Sound Generator yang terus berjalan stabil di latar belakang."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
