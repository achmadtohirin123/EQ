package com.example.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.max

/**
 * Kompresor Dinamis & Limiter Master Presisi Tinggi.
 * Mengurangi rentang dinamis audio (menaikkan bagian pelan, menekan desibel berlebih)
 * serta mencegah distorsi/clipping menggunakan limiter brickwall digital.
 */
class CompressorLimiter {
    // Parameter Kompresor
    var thresholdDb: Float = -12f   // -60dB hingga 0dB
    var ratio: Float = 4f           // 1:1 hingga 20:1
    var attackMs: Float = 10f       // 0.1ms hingga 200ms
    var releaseMs: Float = 100f     // 10ms hingga 1000ms
    var makeupGainDb: Float = 0f    // 0dB hingga 24dB
    var kneeDb: Float = 4f          // Soft knee lebar (dalam dB)
    var isEnabled: Boolean = true

    // State Internal DSP (Detector Envelope)
    private var envelope: Float = 0.001f // Menyimpan tingkat daya sinyal berjalan
    private var sampleRate: Float = 44100f

    // Koefisien waktu berjalan
    private var attackCoef: Float = 0.01f
    private var releaseCoef: Float = 0.001f

    /**
     * Mengatur ulang frekuensi sampel dan memperbarui koefisien pengetukan.
     */
    fun init(sampleRate: Float) {
        this.sampleRate = sampleRate
        recalculateCoefficients()
    }

    fun recalculateCoefficients() {
        // rumus konversi waktu (ms) ke koefisien filter lolos bawah satu orde
        attackCoef = (1.0 - Math.exp(-1.0 / (sampleRate * (attackMs / 1000.0)))).toFloat()
        releaseCoef = (1.0 - Math.exp(-1.0 / (sampleRate * (releaseMs / 1000.0)))).toFloat()
    }

    /**
     * Memproses sampel audio stereo L & R secara bersamaan demi menjaga balance spasial.
     * Mengembalikan array berisi sepasang sampel pasca-pemrosesan: [L, R]
     *
     * @param inL Sampel masukan kiri
     * @param inR Sampel masukan kanan
     * @param outPair Array output berukuran 2 untuk menaruh hasil (bebas alokasi!)
     */
    fun process(inL: Float, inR: Float, outPair: FloatArray) {
        if (!isEnabled) {
            outPair[0] = inL
            outPair[1] = inR
            return
        }

        // 1. Deteksi Tingkat Sinyal Puncak (Peak Detection)
        val peak = max(abs(inL), abs(inR))

        // 2. Deteksi Envelope Berbasis Attack / Release
        // Jika tingkat sinyal puncak saat ini melebihi envelope, maka kita masuk fase Attack.
        // Sebaliknya, jika sinyal menurun, kita masuk fase Release berjalan.
        val coef = if (peak > envelope) attackCoef else releaseCoef
        envelope = envelope + coef * (peak - envelope)

        // Ubah level envelope ke Desibel demi kenyamanan matematika audio
        val envDb = if (envelope < 1e-6f) -120f else 20f * log10(envelope)

        // 3. Hitung Reduksi Penguatan (Gain Reduction)
        var gainReductionDb = 0f
        
        // Kompresor biasa dengan Soft-Knee
        val lowerKneeLimit = thresholdDb - kneeDb / 2f
        val upperKneeLimit = thresholdDb + kneeDb / 2f

        if (envDb > upperKneeLimit) {
            // Kompresi penuh di atas batas lutut atas
            gainReductionDb = (envDb - thresholdDb) * (1f / ratio - 1f)
        } else if (envDb > lowerKneeLimit) {
            // Interpolasi kuadratik di wilayah soft knee
            val kneeFactor = (envDb - lowerKneeLimit) / kneeDb
            gainReductionDb = (envDb - thresholdDb) * (1f / ratio - 1f) * kneeFactor * 0.5f
        }

        // Ubah reduksi penguatan DB kembali ke faktor pengali linier
        // Gain reduction biasanya berskala negatif atau nol desibel
        var gainMultiplier = 10f.pow(gainReductionDb / 20f)

        // 4. Terapkan Compressor Gain & Makeup Gain
        val makeupMultiplier = 10f.pow(makeupGainDb / 20f)
        
        var outL = inL * gainMultiplier * makeupMultiplier
        var outR = inR * gainMultiplier * makeupMultiplier

        // 5. HARD DIGITAL LIMITER - Brickwall Limiter di 1.0f (0dBFS)
        // Mencegah letupan suara digital agar audio tetap jernih dan clip-free.
        val outPeak = max(abs(outL), abs(outR))
        if (outPeak > 1.0f) {
            val limiterGain = 1.0f / outPeak
            outL *= limiterGain
            outR *= limiterGain
        }

        outPair[0] = outL
        outPair[1] = outR
    }

    /**
     * Mengembalikan level Reduksi Penguatan saat ini dalam dB (untuk menggambar grafis VU/GR Meter).
     */
    fun getCurrentGainReductionDb(): Float {
        if (!isEnabled || envelope < 1e-6f) return 0f
        val envDb = if (envelope < 1e-6f) -120f else 20f * log10(envelope)
        val lowerKneeLimit = thresholdDb - kneeDb / 2f
        val upperKneeLimit = thresholdDb + kneeDb / 2f

        return when {
            envDb > upperKneeLimit -> (envDb - thresholdDb) * (1f / ratio - 1f)
            envDb > lowerKneeLimit -> {
                val kneeFactor = (envDb - lowerKneeLimit) / kneeDb
                (envDb - thresholdDb) * (1f / ratio - 1f) * kneeFactor * 0.5f
            }
            else -> 0f
        }
    }
}
