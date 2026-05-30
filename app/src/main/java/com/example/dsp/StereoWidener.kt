package com.example.dsp

import kotlin.math.abs

/**
 * Pemroses Efek Stereo Widener (Mid-Side Matrix) dan 3D Spatial Reverb.
 * Berfungsi untuk memperlebar ruang suara (Stereo Image) secara visual/akustik,
 * serta memberikan nuansa gema ambient (Reverb) yang realistis.
 */
class StereoWidener {
    // Pengaturan Stereo Width: 1.0f = netral, 0.0f = mono murni, >2.0f = ultra lebar
    var stereoWidth: Float = 1.0f
    
    // Tingkat pengembalian Reverb (0.0f hingga 1.0f)
    var reverbLevel: Float = 0.0f

    // Buffer Penundaan (Delay Line) untuk membuat efek spasial 3D / Reverb alami
    private val delayBufferSize = 4410 // Sekitar 100ms tunda pada 44.1kHz
    private val delayBufferL = FloatArray(delayBufferSize)
    private val delayBufferR = FloatArray(delayBufferSize)
    private var delayWriteIndex = 0

    // Konstanta rasio pantulan reverb
    private val feedbackRamp = 0.65f

    /**
     * Memproses stereo sample L & R dan mengembalikan hasil yang sudah diperlebar ruangnya.
     */
    fun process(inL: Float, inR: Float, outPair: FloatArray) {
        // Deklarasikan variabel lokal penampung sampel agar hemat register
        var currentL = inL
        var currentR = inR

        // 1. Terapkan Efek Reverb Digital (Spasial 3D Feedback Delay)
        if (reverbLevel > 0.01f) {
            // Ambil sampel tertunda
            val readIndex = (delayWriteIndex + 1) % delayBufferSize
            val delayedL = delayBufferL[readIndex]
            val delayedR = delayBufferR[readIndex]

            // Hitung output dengan campuran sinyal basah (wet) dan kering (dry)
            currentL = inL * (1f - reverbLevel * 0.4f) + delayedL * reverbLevel * 0.8f
            currentR = inR * (1f - reverbLevel * 0.4f) + delayedR * reverbLevel * 0.8f

            // Masukkan kembali output dengan feedback ke dalam ring buffer delay
            delayBufferL[delayWriteIndex] = inL + delayedL * feedbackRamp
            delayBufferR[delayWriteIndex] = inR + delayedR * feedbackRamp

            // Geser index buffer
            delayWriteIndex = (delayWriteIndex + 1) % delayBufferSize
        }

        // 2. Terapkan Mid-Side Stereo Widener Matrix
        if (stereoWidth != 1.0f) {
            // Persamaan Matriks Mid-Side:
            // Mid = L + R (Sinyal tengah)
            // Side = L - R (Sinyal tepi kiri-kanan)
            val mid = currentL + currentR
            val side = currentL - currentR

            // Kalikan sinyal Side dengan koefisien pelebaran
            val wideSide = side * stereoWidth

            // Kembalikan ke format Left & Right (dibagi 2 agar tidak clipping)
            currentL = (mid + wideSide) * 0.5f
            currentR = (mid - wideSide) * 0.5f
        }

        outPair[0] = currentL
        outPair[1] = currentR
    }

    /**
     * Mengosongkan memori buffer echo/reverb agar tidak berderik saat dinyalakan kembali.
     */
    fun reset() {
        delayBufferL.fill(0f)
        delayBufferR.fill(0f)
        delayWriteIndex = 0
    }
}
