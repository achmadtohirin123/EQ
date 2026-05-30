package com.example.dsp

import kotlin.math.sin
import kotlin.math.PI
import java.util.Random

/**
 * Generator Sinyal Lead Synth & Gelombang Audio Presisi Tinggi.
 * Menghasilkan osilasi digital real-time (Sine, Square, Triangle, Sawtooth, White Noise, dan Ambient LFO Melodic Sequence)
 * untuk menunjukkan daya pemrosesan filter 31band Equalizer, Compressor, dan Space Reverb secara langsung.
 */
class AudioSignalGenerator {
    enum class WaveformType {
        SINE,       // Bass murni hangat
        SQUARE,     // Lead digital gigit (kaya harmonik ganjil)
        TRIANGLE,   // Bass synth retro
        SAWTOOTH,   // Suara gergaji piercing (kaya semua harmonik)
        WHITE_NOISE,// Derau putih (semua frekuensi merata, uji EQ paling mantap!)
        AMBIENT_BEAT // Melodi LFO interaktif yang mensimulasikan musik berjalan
    }

    var waveform: WaveformType = WaveformType.AMBIENT_BEAT
    var frequency: Float = 110f // Frekuensi dasar synth (A2 bass)
    var isPlaying: Boolean = false
    
    // State internal osilator (Fase berjalan)
    private var phase: Float = 0f
    private val sampleRate = 44100f
    private val random = Random()

    // State untuk mode Melodic Ambient Beat
    private var beatStep = 0
    private var sampleCounter = 0
    private val stepDurationSamples = (sampleRate * 0.25f).toInt() // Tempo 120BPM (0.25detik per ketik)
    private var targetFreq = 110f
    private var synthEnvelope = 0f

    // Parameter LFO untuk Ambient Beat
    private var lfoPhaseL = 0f
    private var lfoPhaseR = 0f

    /**
     * Memperoleh sampel audio mono berikutnya dari osilator terpilih.
     * Mengembalikan amplitudo berkisar [-1.0f, 1.0f].
     */
    fun nextSample(pairBuffer: FloatArray) {
        if (!isPlaying) {
            pairBuffer[0] = 0f
            pairBuffer[1] = 0f
            return
        }

        // 1. Logika Update Melodic Ambient Beat jika aktif
        if (waveform == WaveformType.AMBIENT_BEAT) {
            sampleCounter++
            if (sampleCounter >= stepDurationSamples) {
                sampleCounter = 0
                beatStep = (beatStep + 1) % 16
                
                // Urutan bassline techno progresif
                val notes = floatArrayOf(
                    55.0f,  // A1
                    55.0f,
                    110.0f, // A2 (oktaf atas)
                    82.4f,  // E2
                    110.0f,
                    73.4f,  // D2
                    55.0f,
                    130.8f, // C3
                    55.0f,
                    55.0f,
                    110.0f,
                    98.0f,  // G2
                    110.0f,
                    65.4f,  // C2
                    55.0f,
                    146.8f  // D3
                )
                
                var note = notes[beatStep]
                // Berikan sentuhan variasi ketukan
                if (beatStep % 4 == 0) {
                    note *= 1.5f // Harmoni Kelima
                }
                
                targetFreq = note
                synthEnvelope = 1.0f // Trigger attack ampliduto amplop instan
            }

            // Slide frekuensi (portamento) agar bass geser halus (tidak berbunyi "klik")
            frequency = frequency + 0.08f * (targetFreq - frequency)
            
            // Peluruhan eksponensial envelope volume
            synthEnvelope *= 0.9997f
        }

        // 2. Hitung Penambahan Fase Berbasis Frekuensi Dinamis
        val phaseIncrement = (2f * PI.toFloat() * frequency) / sampleRate
        phase += phaseIncrement
        if (phase > 2f * PI.toFloat()) {
            phase -= 2f * PI.toFloat()
        }

        // 3. Sintesis Gelombang Terpilih
        var monoSample = 0f
        when (waveform) {
            WaveformType.SINE -> {
                monoSample = sin(phase)
            }
            WaveformType.SQUARE -> {
                monoSample = if (phase < PI) 0.3f else -0.3f
            }
            WaveformType.TRIANGLE -> {
                val value = phase / (2f * PI.toFloat())
                monoSample = (if (value < 0.5f) {
                    4f * value - 1f
                } else {
                    -4f * value + 3f
                }) * 0.5f
            }
            WaveformType.SAWTOOTH -> {
                monoSample = ((phase / (2f * PI.toFloat())) * 2f - 1f) * 0.4f
            }
            WaveformType.WHITE_NOISE -> {
                monoSample = (random.nextFloat() * 2f - 1f) * 0.25f // Volume diperkecil agar tidak berisik berlebih
            }
            WaveformType.AMBIENT_BEAT -> {
                // Melodic sound combine Sine bass & Sawtooth lead
                val subBass = sin(phase * 0.5f) * 0.6f // Sub-Bass sine ultra dalam
                
                // Sawtooth lead dengan envelope decay
                val leadPhase = phase * 2f % (2f * PI.toFloat())
                val leadSynth = ((leadPhase / (2f * PI.toFloat())) * 2f - 1f) * 0.15f * synthEnvelope
                
                // Klik transient hi-hat palsu tiap beat genap
                var hatNoise = 0f
                if (beatStep % 2 == 1 && sampleCounter < 1500) {
                    val decay = (1500f - sampleCounter) / 1500f
                    hatNoise = (random.nextFloat() * 2f - 1f) * 0.08f * decay
                }

                monoSample = subBass + leadSynth + hatNoise
            }
        }

        // 4. Modulasi Spasial LFO Mandiri untuk Kiri & Kanan (stereo widening alami!)
        if (waveform == WaveformType.AMBIENT_BEAT) {
            lfoPhaseL += 0.0001f
            lfoPhaseR += 0.00015f
            
            val modL = 0.8f + 0.2f * sin(lfoPhaseL)
            val modR = 0.8f + 0.2f * sin(lfoPhaseR)
            
            pairBuffer[0] = monoSample * modL
            pairBuffer[1] = monoSample * modR
        } else {
            pairBuffer[0] = monoSample
            pairBuffer[1] = monoSample
        }
    }
}
