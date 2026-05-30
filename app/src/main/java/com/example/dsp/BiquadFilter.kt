package com.example.dsp

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * Filter Biquad IIR Presisi Tinggi untuk Pemrosesan Audio Digital (DSP).
 * Digunakan untuk mengimplementasikan Equalizer parametrik (Peaking EQ),
 * Low-pass, High-pass, Bass Boost, dan Treble Boost.
 *
 * Implementasi dirancang agar bebas dari alokasi objek baru di dalam audio thread
 * guna mencegah terjadinya "Garbage Collection jank" (lag/interupsi suara).
 */
class BiquadFilter {
    // Koefisien Filter
    private var b0: Float = 1f
    private var b1: Float = 0f
    private var b2: Float = 0f
    private var a1: Float = 0f
    private var a2: Float = 0f

    // Buffer Memori State (Delay Lines) L/R terpisah jika stereo, atau mono tunggal
    private var x1_L: Float = 0f
    private var x2_L: Float = 0f
    private var y1_L: Float = 0f
    private var y2_L: Float = 0f

    private var x1_R: Float = 0f
    private var x2_R: Float = 0f
    private var y1_R: Float = 0f
    private var y2_R: Float = 0f

    // Parameter Filter saat ini
    var frequency: Float = 1000f
        private set
    var q: Float = 1f
        private set
    var gainDb: Float = 0f
        private set

    /**
     * Mengatur koefisien untuk Peaking EQ filter (Parametrik EQ Band).
     *
     * @param centerFreq Frekuensi tengah (Hz)
     * @param bandwidthQ Faktor Q (lebar kurva, default 1.41)
     * @param gainDb Penguatan atau pelemahan dalam Desibel (dB)
     * @param sampleRate Frekuensi sampling audio (misal 44100Hz atau 48000Hz)
     */
    fun configurePeakingEQ(centerFreq: Float, bandwidthQ: Float, gainDb: Float, sampleRate: Float) {
        this.frequency = centerFreq
        this.q = if (bandwidthQ < 0.1f) 0.1f else bandwidthQ
        this.gainDb = gainDb

        // Bypass jika gain sangat dekat ke 0 dB untuk menghemat pemrosesan
        if (kotlin.math.abs(gainDb) < 0.05f) {
            b0 = 1f; b1 = 0f; b2 = 0f
            a1 = 0f; a2 = 0f
            return
        }

        val fs = sampleRate
        val w0 = (2.0 * Math.PI * centerFreq / fs).toFloat()
        val alpha = (sin(w0) / (2.0 * q)).toFloat()
        val a = Math.pow(10.0, gainDb.toDouble() / 40.0).toFloat()

        val cosW0 = cos(w0)

        // Rumus Biquad Peaking EQ standard
        val b0_raw = 1f + alpha * a
        val b1_raw = -2f * cosW0
        val b2_raw = 1f - alpha * a
        val a0_raw = 1f + alpha / a
        val a1_raw = -2f * cosW0
        val a2_raw = 1f - alpha / a

        // Normalisasi koefisien dengan membagi semua dengan a0_raw
        b0 = b0_raw / a0_raw
        b1 = b1_raw / a0_raw
        b2 = b2_raw / a0_raw
        a1 = a1_raw / a0_raw
        a2 = a2_raw / a0_raw
    }

    /**
     * Mengatur koefisien untuk Low-Pass Filter (LPF).
     */
    fun configureLowPass(cutoffFreq: Float, resonanceQ: Float, sampleRate: Float) {
        this.frequency = cutoffFreq
        this.q = if (resonanceQ < 0.1f) 0.1f else resonanceQ
        this.gainDb = 0f

        val fs = sampleRate
        val w0 = (2.0 * Math.PI * cutoffFreq / fs).toFloat()
        val alpha = (sin(w0) / (2.0 * q)).toFloat()
        val cosW0 = cos(w0)

        val b0_raw = (1f - cosW0) / 2f
         val b1_raw = 1f - cosW0
         val b2_raw = (1f - cosW0) / 2f
         val a0_raw = 1f + alpha
         val a1_raw = -2f * cosW0
         val a2_raw = 1f - alpha

        b0 = b0_raw / a0_raw
        b1 = b1_raw / a0_raw
        b2 = b2_raw / a0_raw
        a1 = a1_raw / a0_raw
        a2 = a2_raw / a0_raw
    }

    /**
     * Mengatur koefisien untuk High-Pass Filter (HPF).
     */
    fun configureHighPass(cutoffFreq: Float, resonanceQ: Float, sampleRate: Float) {
        this.frequency = cutoffFreq
        this.q = if (resonanceQ < 0.1f) 0.1f else resonanceQ
        this.gainDb = 0f

        val fs = sampleRate
        val w0 = (2.0 * Math.PI * cutoffFreq / fs).toFloat()
        val alpha = (sin(w0) / (2.0 * q)).toFloat()
        val cosW0 = cos(w0)

        val b0_raw = (1f + cosW0) / 2f
        val b1_raw = -(1f + cosW0)
        val b2_raw = (1f + cosW0) / 2f
        val a0_raw = 1f + alpha
        val a1_raw = -2f * cosW0
        val a2_raw = 1f - alpha

        b0 = b0_raw / a0_raw
        b1 = b1_raw / a0_raw
        b2 = b2_raw / a0_raw
        a1 = a1_raw / a0_raw
        a2 = a2_raw / a0_raw
    }

    /**
     * Memproses satu sample audio untuk channel Kiri (Left).
     * Fungsi inline berkinerja tinggi, bebas alokasi memori.
     */
    fun processLeft(input: Float): Float {
        // Persamaan perbedaan Direct Form I
        val output = b0 * input + b1 * x1_L + b2 * x2_L - a1 * y1_L - a2 * y2_L
        
        // Update memori delay line
        x2_L = x1_L
        x1_L = input
        y2_L = y1_L
        y1_L = output

        return output
    }

    /**
     * Memproses satu sample audio untuk channel Kanan (Right).
     */
    fun processRight(input: Float): Float {
        val output = b0 * input + b1 * x1_R + b2 * x2_R - a1 * y1_R - a2 * y2_R
        
        x2_R = x1_R
        x1_R = input
        y2_R = y1_R
        y1_R = output

        return output
    }

    /**
     * Mereset state memory filter untuk menghindari noise meledak (instabilitas).
     */
    fun reset() {
        x1_L = 0f; x2_L = 0f; y1_L = 0f; y2_L = 0f
        x1_R = 0f; x2_R = 0f; y1_R = 0f; y2_R = 0f
    }
}
