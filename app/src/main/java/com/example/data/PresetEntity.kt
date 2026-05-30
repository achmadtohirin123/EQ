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
