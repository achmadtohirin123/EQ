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
@Database(entities = [PresetEntity::class], version = 2, exportSchema = false)
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
                    "bro_eq_josjis_db"
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
            // 1. DANGDUT JOSS: Kendang & suling jernih
            val dangdutFreqs = FloatArray(31) { 0f }
            dangdutFreqs[2] = 4.5f   // 31.5Hz
            dangdutFreqs[3] = 6.0f   // 40Hz
            dangdutFreqs[4] = 8.5f   // 50Hz
            dangdutFreqs[5] = 9.0f   // 63Hz
            dangdutFreqs[6] = 7.5f   // 80Hz
            dangdutFreqs[12] = 2.0f  // 250Hz
            dangdutFreqs[18] = 3.5f  // 1kHz
            dangdutFreqs[19] = 4.0f
            dangdutFreqs[20] = 4.5f
            dangdutFreqs[25] = 5.0f  // 5k
            dangdutFreqs[26] = 6.5f
            dangdutFreqs[27] = 7.0f
            dangdutFreqs[28] = 5.0f

            // 2. DUGEM MANTAP: Sub-bass gleer padat & high crisp
            val dugemFreqs = FloatArray(31) { 0f }
            for (i in 0..6) dugemFreqs[i] = 10.0f - (6 - i) * 0.8f
            dugemFreqs[7] = 8.0f
            dugemFreqs[8] = 6.0f
            for (i in 10..20) dugemFreqs[i] = -3.5f
            dugemFreqs[24] = 4.0f
            dugemFreqs[25] = 6.0f
            dugemFreqs[26] = 8.0f
            dugemFreqs[27] = 10.0f
            dugemFreqs[28] = 9.0f
            dugemFreqs[29] = 8.0f

            // 3. ROCK CETAR: Bass solid, mid-high agresif
            val rockFreqs = FloatArray(31) { 0f }
            rockFreqs[4] = 4.0f
            rockFreqs[5] = 5.0f
            rockFreqs[6] = 3.5f
            rockFreqs[10] = 1.5f
            rockFreqs[14] = -1.5f
            rockFreqs[18] = 3.0f
            rockFreqs[20] = 5.5f
            rockFreqs[22] = 6.0f
            rockFreqs[24] = 4.5f
            rockFreqs[27] = 5.0f

            // 4. SHOLAWAT ADEM: Vokal hangat mengalun, reverb subur
            val adamFreqs = FloatArray(31) { 0f }
            for (i in 3..7) adamFreqs[i] = 2.0f
            for (i in 14..22) adamFreqs[i] = 4.0f
            adamFreqs[25] = 3.0f
            adamFreqs[26] = 3.5f
            adamFreqs[27] = 3.0f

            // 5. POP PREMIUM: V-Shape seimbang dan empuk
            val popFreqs = FloatArray(31) { 0f }
            popFreqs[3] = 3.0f
            popFreqs[4] = 3.5f
            popFreqs[5] = 4.0f
            popFreqs[6] = 3.0f
            popFreqs[10] = -1.0f
            popFreqs[14] = -1.5f
            popFreqs[18] = 1.0f
            popFreqs[22] = 3.0f
            popFreqs[25] = 4.5f
            popFreqs[27] = 5.0f
            popFreqs[28] = 4.0f

            // 6. JAZZ RELAX: Low hangat, mid tebal, high halus anti-hiss
            val jazzFreqs = FloatArray(31) { 0f }
            jazzFreqs[3] = 4.5f
            jazzFreqs[4] = 4.0f
            jazzFreqs[5] = 3.5f
            jazzFreqs[8] = 2.0f
            for (i in 12..18) jazzFreqs[i] = 2.5f
            jazzFreqs[22] = 1.0f
            jazzFreqs[25] = -1.5f
            jazzFreqs[27] = -2.0f

            // 7. ACOUSTIC CLEAN: Petikan gitar garing & artikulatif
            val acousticFreqs = FloatArray(31) { 0f }
            acousticFreqs[4] = 2.0f
            acousticFreqs[8] = 1.5f
            acousticFreqs[12] = 1.0f
            for (i in 17..23) acousticFreqs[i] = 3.5f
            acousticFreqs[25] = 4.0f
            acousticFreqs[27] = 4.5f

            // 8. SUPER BASS BOOSTER: Menggelegar, bergetar fokus sub-bass bawah
            val superBassFreqs = FloatArray(31) { 0f }
            for (i in 0..5) superBassFreqs[i] = 12.0f
            superBassFreqs[6] = 9.0f
            superBassFreqs[7] = 6.0f
            superBassFreqs[8] = 3.0f
            for (i in 12..22) superBassFreqs[i] = -2.0f

            // 9. VOCAL CLARITY: Mengangkat kejelasan dialog, podcast, & film
            val vocalFreqs = FloatArray(31) { 0f }
            for (i in 0..5) vocalFreqs[i] = -6.0f  // Redam rumble
            for (i in 12..23) vocalFreqs[i] = 5.0f  // Angkat vokal
            vocalFreqs[25] = 3.0f
            vocalFreqs[27] = 2.0f

            // 10. EDM TRANCE STORM: Kick padat & synth garing gemerlap
            val edmFreqs = FloatArray(31) { 0f }
            for (i in 3..6) edmFreqs[i] = 7.0f
            for (i in 12..16) edmFreqs[i] = -2.0f
            edmFreqs[22] = 3.5f
            edmFreqs[25] = 6.0f
            edmFreqs[27] = 8.5f
            edmFreqs[28] = 7.0f

            // 11. HIP HOP BOOMBOAP: Kick klasik jedug padat keras
            val hiphopFreqs = FloatArray(31) { 0f }
            hiphopFreqs[4] = 8.0f
            hiphopFreqs[5] = 8.5f
            hiphopFreqs[6] = 7.0f
            hiphopFreqs[10] = 2.0f
            for (i in 14..20) hiphopFreqs[i] = 1.5f
            hiphopFreqs[25] = 3.0f
            hiphopFreqs[27] = 4.0f

            // 12. CINEMATIC SURROUND: Teatrikal lebar & megah
            val cinemaFreqs = FloatArray(31) { 0f }
            cinemaFreqs[2] = 6.0f
            cinemaFreqs[3] = 6.5f
            cinemaFreqs[4] = 5.0f
            cinemaFreqs[10] = -1.0f
            cinemaFreqs[15] = 2.0f
            cinemaFreqs[20] = 3.0f
            cinemaFreqs[25] = 4.5f
            cinemaFreqs[27] = 6.0f
            cinemaFreqs[28] = 5.0f

            // 13. FLAT standard
            val flatGains = List(31) { 0.0f }

            val pDangdut = PresetEntity(
                name = "DANGDUT JOSS💥",
                eqGainsString = PresetEntity.convertGainsToString(dangdutFreqs.toList()),
                compThresholdDb = -14f,
                compRatio = 4.5f,
                compAttackMs = 12f,
                compReleaseMs = 150f,
                compMakeupGainDb = 3.5f,
                stereoWidth = 1.35f,
                bassBoostDb = 6.5f,
                reverbLevel = 0.15f,
                isSystemPreset = true,
                isSelected = true
            )

            val pDugem = PresetEntity(
                name = "DUGEM MANTAP🔊",
                eqGainsString = PresetEntity.convertGainsToString(dugemFreqs.toList()),
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
                eqGainsString = PresetEntity.convertGainsToString(rockFreqs.toList()),
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
                eqGainsString = PresetEntity.convertGainsToString(adamFreqs.toList()),
                compThresholdDb = -12f,
                compRatio = 3.0f,
                compAttackMs = 25f,
                compReleaseMs = 250f,
                compMakeupGainDb = 1.5f,
                stereoWidth = 1.45f,
                bassBoostDb = 2.0f,
                reverbLevel = 0.55f,
                isSystemPreset = true,
                isSelected = false
            )

            val pPop = PresetEntity(
                name = "POP PREMIUM🎤",
                eqGainsString = PresetEntity.convertGainsToString(popFreqs.toList()),
                compThresholdDb = -15f,
                compRatio = 4.0f,
                compAttackMs = 10f,
                compReleaseMs = 120f,
                compMakeupGainDb = 3.0f,
                stereoWidth = 1.25f,
                bassBoostDb = 4.0f,
                reverbLevel = 0.15f,
                isSystemPreset = true,
                isSelected = false
            )

            val pJazz = PresetEntity(
                name = "JAZZ RELAX🎷",
                eqGainsString = PresetEntity.convertGainsToString(jazzFreqs.toList()),
                compThresholdDb = -12f,
                compRatio = 2.5f,
                compAttackMs = 20f,
                compReleaseMs = 180f,
                compMakeupGainDb = 2.0f,
                stereoWidth = 1.3f,
                bassBoostDb = 3.5f,
                reverbLevel = 0.12f,
                isSystemPreset = true,
                isSelected = false
            )

            val pAcoustic = PresetEntity(
                name = "ACOUSTIC CLEAN🎸",
                eqGainsString = PresetEntity.convertGainsToString(acousticFreqs.toList()),
                compThresholdDb = -10f,
                compRatio = 2.0f,
                compAttackMs = 15f,
                compReleaseMs = 100f,
                compMakeupGainDb = 1.5f,
                stereoWidth = 1.2f,
                bassBoostDb = 2.0f,
                reverbLevel = 0.1f,
                isSystemPreset = true,
                isSelected = false
            )

            val pSuperBass = PresetEntity(
                name = "SUPER BASS BOOSTER🔥",
                eqGainsString = PresetEntity.convertGainsToString(superBassFreqs.toList()),
                compThresholdDb = -20f,
                compRatio = 5.5f,
                compAttackMs = 8f,
                compReleaseMs = 140f,
                compMakeupGainDb = 5.0f,
                stereoWidth = 1.5f,
                bassBoostDb = 12.0f, // Getar Pol!
                reverbLevel = 0.2f,
                isSystemPreset = true,
                isSelected = false
            )

            val pVocal = PresetEntity(
                name = "VOCAL & DIALOG🗣️",
                eqGainsString = PresetEntity.convertGainsToString(vocalFreqs.toList()),
                compThresholdDb = -14f,
                compRatio = 3.5f,
                compAttackMs = 15f,
                compReleaseMs = 150f,
                compMakeupGainDb = 2.5f,
                stereoWidth = 1.0f,
                bassBoostDb = 0f,
                reverbLevel = 0.08f,
                isSystemPreset = true,
                isSelected = false
            )

            val pEdm = PresetEntity(
                name = "EDM STORM⚡",
                eqGainsString = PresetEntity.convertGainsToString(edmFreqs.toList()),
                compThresholdDb = -16f,
                compRatio = 5.0f,
                compAttackMs = 10f,
                compReleaseMs = 110f,
                compMakeupGainDb = 4.0f,
                stereoWidth = 1.6f,
                bassBoostDb = 8.0f,
                reverbLevel = 0.18f,
                isSystemPreset = true,
                isSelected = false
            )

            val pHipHop = PresetEntity(
                name = "HIP HOP BEAT🎧",
                eqGainsString = PresetEntity.convertGainsToString(hiphopFreqs.toList()),
                compThresholdDb = -15f,
                compRatio = 4.0f,
                compAttackMs = 12f,
                compReleaseMs = 130f,
                compMakeupGainDb = 3.5f,
                stereoWidth = 1.4f,
                bassBoostDb = 7.5f,
                reverbLevel = 0.12f,
                isSystemPreset = true,
                isSelected = false
            )

            val pCinema = PresetEntity(
                name = "CINEMATIC SPACE🎬",
                eqGainsString = PresetEntity.convertGainsToString(cinemaFreqs.toList()),
                compThresholdDb = -18f,
                compRatio = 4.5f,
                compAttackMs = 18f,
                compReleaseMs = 200f,
                compMakeupGainDb = 4.0f,
                stereoWidth = 2.0f, // Surround super lebar
                bassBoostDb = 6.0f,
                reverbLevel = 0.45f, // Reverb sinematik
                isSystemPreset = true,
                isSelected = false
            )

            val pFlat = PresetEntity(
                name = "FLAT standard⚖️",
                eqGainsString = PresetEntity.convertGainsToString(flatGains),
                compThresholdDb = -12f,
                compRatio = 1.0f,
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
            dao.insertPreset(pPop)
            dao.insertPreset(pJazz)
            dao.insertPreset(pAcoustic)
            dao.insertPreset(pSuperBass)
            dao.insertPreset(pVocal)
            dao.insertPreset(pEdm)
            dao.insertPreset(pHipHop)
            dao.insertPreset(pCinema)
            dao.insertPreset(pFlat)
        }
    }
}
