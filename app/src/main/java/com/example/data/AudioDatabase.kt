package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Pemegang Database Room utama untuk database audio lokal.
 */
@Database(entities = [PresetEntity::class], version = 1, exportSchema = false)
abstract class AudioDatabase : RoomDatabase() {
    abstract fun presetDao(): PresetDao

    companion object {
        @Volatile
        private var INSTANCE: AudioDatabase? = null

        fun getDatabase(context: Context): AudioDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AudioDatabase::class.java,
                    "bro_eq_josss_db"
                )
                .fallbackToDestructiveMigration()
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Lakukan insersi awal preset legendaris dalam thread terpisah
                        INSTANCE?.let { database ->
                            CoroutineScope(Dispatchers.IO).launch {
                                populateDefaultPresets(database.presetDao())
                            }
                        }
                    }
                })
                .build()
                
                INSTANCE = instance
                instance
            }
        }

        /**
         * Mengisi preset default standar premium khas penikmat audio Indonesia secara otomatis.
         */
        suspend fun populateDefaultPresets(dao: PresetDao) {
            // Equalizer 31 Band (gains untuk frekuensi: 20Hz ke 20kHz secara logaritmis)
            // Kami pasang variasi gain yang pas representatif untuk masing-masing genre.
            
            // 1. DANGDUT JOSS: Menitikberatkan suara kendang (80Hz, 100Hz) dan vokal jernih (1kHz ke 4kHz).
            val dangdutFreqs = FloatArray(31) { 0f }
            dangdutFreqs[2] = 4.5f   // 31.5Hz
            dangdutFreqs[3] = 6.0f   // 40Hz
            dangdutFreqs[4] = 8.5f   // 50Hz (Jedug kendang tak)
            dangdutFreqs[5] = 9.0f   // 63Hz
            dangdutFreqs[6] = 7.5f   // 80Hz
            dangdutFreqs[12] = 2.0f  // 250Hz
            dangdutFreqs[18] = 3.5f  // 1kHz (Suling & Vokal)
            dangdutFreqs[19] = 4.0f  // 1.25kHz
            dangdutFreqs[20] = 4.5f  // 1.6kHz
            dangdutFreqs[25] = 5.0f  // 5k (Kecrek ces)
            dangdutFreqs[26] = 6.5f  // 6.3k
            dangdutFreqs[27] = 7.0f  // 8k
            dangdutFreqs[28] = 5.0f  // 10k
            val dangdutGains = dangdutFreqs.toList()

            // 2. DUGEM MANTAP: Sub-bass gleer (30Hz ke 60Hz), treble tajam (8kHz ke 16kHz) dan mid diredam (vokal surut).
            val dugemFreqs = FloatArray(31) { 0f }
            for (i in 0..6) dugemFreqs[i] = 10.0f - (6 - i) * 0.8f // Sub-bass gleer padat!
            dugemFreqs[7] = 8.0f
            dugemFreqs[8] = 6.0f
            for (i in 10..20) dugemFreqs[i] = -3.5f // Vokal tersurut ke belakang agar musik mendominasi
            dugemFreqs[24] = 4.0f
            dugemFreqs[25] = 6.0f
            dugemFreqs[26] = 8.0f  // Treble garing
            dugemFreqs[27] = 10.0f
            dugemFreqs[28] = 9.0f
            dugemFreqs[29] = 8.0f
            val dugemGains = dugemFreqs.toList()

            // 3. ROCK CETAR: Bass solid, mid-high agresif untuk gitar elektrik (2kHz - 4kHz), high renyah.
            val rockFreqs = FloatArray(31) { 0f }
            rockFreqs[4] = 4.0f
            rockFreqs[5] = 5.0f
            rockFreqs[6] = 3.5f
            rockFreqs[10] = 1.5f
            rockFreqs[14] = -1.5f
            rockFreqs[18] = 3.0f
            rockFreqs[20] = 5.5f  // Distorsi gitar menonjol
            rockFreqs[22] = 6.0f
            rockFreqs[24] = 4.5f
            rockFreqs[27] = 5.0f
            val rockGains = rockFreqs.toList()

            // 4. SHOLAWAT ADEM: Treble halus (untuk kejelasan rebana), vokal hangat (mid tebal), reverb subur.
            val adamFreqs = FloatArray(31) { 0f }
            for (i in 3..7) adamFreqs[i] = 2.0f
            for (i in 14..22) adamFreqs[i] = 4.0f // Vokal hangat mengalun
            adamFreqs[25] = 3.0f
            adamFreqs[26] = 3.5f
            adamFreqs[27] = 3.0f
            val ademGains = adamFreqs.toList()

            // 5. FLAT (Datar): 0dB di seluruh spektrum
            val flatGains = List(31) { 0.0f }

            val pDangdut = PresetEntity(
                name = "DANGDUT JOSS💥",
                eqGainsString = PresetEntity.convertGainsToString(dangdutGains),
                compThresholdDb = -14f,
                compRatio = 4.5f,
                compAttackMs = 12f,
                compReleaseMs = 150f,
                compMakeupGainDb = 3.5f,
                stereoWidth = 1.35f,
                bassBoostDb = 6.5f,
                reverbLevel = 0.15f,
                isSystemPreset = true,
                isSelected = true // Dipilih pertama kali secara default
            )

            val pDugem = PresetEntity(
                name = "DUGEM MANTAP🔊",
                eqGainsString = PresetEntity.convertGainsToString(dugemGains),
                compThresholdDb = -18f,
                compRatio = 6f,
                compAttackMs = 8f,
                compReleaseMs = 120f,
                compMakeupGainDb = 5.0f,
                stereoWidth = 1.8f,
                bassBoostDb = 9.0f,
                reverbLevel = 0.25f,
                isSystemPreset = true,
                isSelected = false
            )

            val pRock = PresetEntity(
                name = "ROCK CETAR🎸",
                eqGainsString = PresetEntity.convertGainsToString(rockGains),
                compThresholdDb = -10f,
                compRatio = 3.5f,
                compAttackMs = 15f,
                compReleaseMs = 100f,
                compMakeupGainDb = 2.0f,
                stereoWidth = 1.2f,
                bassBoostDb = 3.0f,
                reverbLevel = 0.08f,
                isSystemPreset = true,
                isSelected = false
            )

            val pAdem = PresetEntity(
                name = "SHOLAWAT ADEM🕌",
                eqGainsString = PresetEntity.convertGainsToString(ademGains),
                compThresholdDb = -12f,
                compRatio = 3.0f,
                compAttackMs = 25f,
                compReleaseMs = 250f,
                compMakeupGainDb = 1.5f,
                stereoWidth = 1.45f,
                bassBoostDb = 2.0f,
                reverbLevel = 0.55f, // Reverb subur melayang
                isSystemPreset = true,
                isSelected = false
            )

            val pFlat = PresetEntity(
                name = "FLAT standard⚖️",
                eqGainsString = PresetEntity.convertGainsToString(flatGains),
                compThresholdDb = -12f,
                compRatio = 1.0f, // No compression
                compAttackMs = 10f,
                compReleaseMs = 100f,
                compMakeupGainDb = 0f,
                stereoWidth = 1.0f,
                bassBoostDb = 0f,
                reverbLevel = 0f,
                isSystemPreset = true,
                isSelected = false
            )

            dao.insertPreset(pDangdut)
            dao.insertPreset(pDugem)
            dao.insertPreset(pRock)
            dao.insertPreset(pAdem)
            dao.insertPreset(pFlat)
        }
    }
}
