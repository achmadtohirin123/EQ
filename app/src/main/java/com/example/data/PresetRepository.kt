package com.example.data

import kotlinx.coroutines.flow.Flow

/**
 * Repositori Penyimpanan Preset Audio untuk mengabstraksikan akses DAO dari UI & Service.
 * Selaras dengan Mandat Room Database Integration.
 */
class PresetRepository(private val presetDao: PresetDao) {
    val allPresets: Flow<List<PresetEntity>> = presetDao.getAllPresetsFlow()

    suspend fun getActivePreset(): PresetEntity? {
        return presetDao.getActivePreset()
    }

    suspend fun selectPreset(id: Int) {
        presetDao.deactivateAllPresets()
        presetDao.selectPresetById(id)
    }

    suspend fun saveCustomPreset(name: String, gains: List<Float>, thresholdDb: Float, ratio: Float, attack: Float, release: Float, makeup: Float, width: Float, bass: Float, reverb: Float) {
        val gainsStr = PresetEntity.convertGainsToString(gains)
        val entity = PresetEntity(
            name = name,
            eqGainsString = gainsStr,
            compThresholdDb = thresholdDb,
            compRatio = ratio,
            compAttackMs = attack,
            compReleaseMs = release,
            compMakeupGainDb = makeup,
            stereoWidth = width,
            bassBoostDb = bass,
            reverbLevel = reverb,
            isSystemPreset = false,
            isSelected = true
        )
        // Nonaktifkan pilihan preset lama
        presetDao.deactivateAllPresets()
        presetDao.insertPreset(entity)
    }

    suspend fun updatePresetSelection(preset: PresetEntity) {
        presetDao.updatePreset(preset)
    }

    suspend fun deletePresetById(id: Int) {
        presetDao.deleteCustomPreset(id)
    }

    suspend fun ensureDefaultPresetsPopulated() {
        if (presetDao.getPresetsCount() == 0) {
            AudioDatabase.populateDefaultPresets(presetDao)
        }
    }
}
