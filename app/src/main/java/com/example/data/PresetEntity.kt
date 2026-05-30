package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entitas Database Room untuk Menyimpan Preset Equalizer & Konfigurasi Efek Audio.
 * Menyimpan konfigurasi 31 band grafis, kompresor master, stereo widener, bass booster, dan reverb.
 */
@Entity(tableName = "audio_presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    
    // 31 Filter EQ gain disimpan sebagai string dengan kompresi koma (misal: "0.0,2.5,-1.0,...")
    val eqGainsString: String,
    
    // Konfigurasi Dynamic Compressor
    val compThresholdDb: Float = -12f,
    val compRatio: Float = 4f,
    val compAttackMs: Float = 10f,
    val compReleaseMs: Float = 100f,
    val compMakeupGainDb: Float = 0f,
    
    // Spasial & Penguat Lainnya
    val stereoWidth: Float = 1.0f,
    val bassBoostDb: Float = 0.0f, // Subharmonic Bass Booster
    val reverbLevel: Float = 0.0f,

    // Konfigurasi Digital Audio Crossover Realtime (4-Way Splitting: Sub, Low, Mid, High)
    val crossoverSubLowHz: Float = 80f,
    val crossoverLowMidHz: Float = 250f,
    val crossoverMidHighHz: Float = 3500f,
    
    val crossoverSubGain: Float = 0f,
    val crossoverLowGain: Float = 0f,
    val crossoverMidGain: Float = 0f,
    val crossoverHighGain: Float = 0f,
    
    val crossoverSubMute: Boolean = false,
    val crossoverLowMute: Boolean = false,
    val crossoverMidMute: Boolean = false,
    val crossoverHighMute: Boolean = false,
    
    val crossoverSubSolo: Boolean = false,
    val crossoverLowSolo: Boolean = false,
    val crossoverMidSolo: Boolean = false,
    val crossoverHighSolo: Boolean = false,
    
    val crossoverSubInvert: Boolean = false,
    val crossoverLowInvert: Boolean = false,
    val crossoverMidInvert: Boolean = false,
    val crossoverHighInvert: Boolean = false,

    // Konfigurasi Professional Audio Limiter (Batas Output Anti-Over)
    val limiterThresholdDb: Float = 0f,
    val limiterReleaseMs: Float = 100f,
    val limiterCeilingDb: Float = -0.1f,
    val limiterKneeDb: Float = 0f,
    val isLimiterEnabled: Boolean = true,
    
    // Flag penanda apakah preset ini yang sedang dipilih
    val isSystemPreset: Boolean = false,
    val isSelected: Boolean = false
) {
    /**
     * Parsing string berkoma menjadi list list gain desibel Float.
     */
    fun getGainsList(): List<Float> {
        return try {
            eqGainsString.split(",").map { it.toFloat() }
        } catch (e: Exception) {
            // Fallback default 31 band datar (0dB) jika parsing error
            List(31) { 0.0f }
        }
    }

    companion object {
        /**
         * Helper utility untuk mengubah list float ke string database berkoma.
         */
        fun convertGainsToString(gains: List<Float>): String {
            return gains.joinToString(",") { String.format("%.2f", it) }
        }
    }
}
