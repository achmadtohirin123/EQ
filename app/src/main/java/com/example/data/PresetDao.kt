package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) untuk memanipulasi baris data tabel Preset Audio.
 */
@Dao
interface PresetDao {
    @Query("SELECT * FROM audio_presets ORDER BY isSystemPreset DESC, name ASC")
    fun getAllPresetsFlow(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM audio_presets WHERE isSelected = 1 LIMIT 1")
    suspend fun getActivePreset(): PresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: PresetEntity)

    @Update
    suspend fun updatePreset(preset: PresetEntity)

    @Query("UPDATE audio_presets SET isSelected = 0")
    suspend fun deactivateAllPresets()

    @Query("UPDATE audio_presets SET isSelected = 1 WHERE id = :id")
    suspend fun selectPresetById(id: Int)

    @Query("DELETE FROM audio_presets WHERE id = :id AND isSystemPreset = 0")
    suspend fun deleteCustomPreset(id: Int)

    @Query("SELECT COUNT(*) FROM audio_presets")
    suspend fun getPresetsCount(): Int
}
