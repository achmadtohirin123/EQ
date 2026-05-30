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

    suspend fun saveCustomPreset(
        name: String,
        gains: List<Float>,
        thresholdDb: Float,
        ratio: Float,
        attack: Float,
        release: Float,
        makeup: Float,
        width: Float,
        bass: Float,
        reverb: Float,
        
        crossoverSubLowHz: Float = 80f,
        crossoverLowMidHz: Float = 250f,
        crossoverMidHighHz: Float = 3500f,
        
        crossoverSubGain: Float = 0f,
        crossoverLowGain: Float = 0f,
        crossoverMidGain: Float = 0f,
        crossoverHighGain: Float = 0f,
        
        crossoverSubMute: Boolean = false,
        crossoverLowMute: Boolean = false,
        crossoverMidMute: Boolean = false,
        crossoverHighMute: Boolean = false,
        
        crossoverSubSolo: Boolean = false,
        crossoverLowSolo: Boolean = false,
        crossoverMidSolo: Boolean = false,
        crossoverHighSolo: Boolean = false,
        
        crossoverSubInvert: Boolean = false,
        crossoverLowInvert: Boolean = false,
        crossoverMidInvert: Boolean = false,
        crossoverHighInvert: Boolean = false,
        
        limiterThresholdDb: Float = 0f,
        limiterReleaseMs: Float = 100f,
        limiterCeilingDb: Float = -0.1f,
        limiterKneeDb: Float = 0f,
        isLimiterEnabled: Boolean = true
    ) {
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
            
            crossoverSubLowHz = crossoverSubLowHz,
            crossoverLowMidHz = crossoverLowMidHz,
            crossoverMidHighHz = crossoverMidHighHz,
            
            crossoverSubGain = crossoverSubGain,
            crossoverLowGain = crossoverLowGain,
            crossoverMidGain = crossoverMidGain,
            crossoverHighGain = crossoverHighGain,
            
            crossoverSubMute = crossoverSubMute,
            crossoverLowMute = crossoverLowMute,
            crossoverMidMute = crossoverMidMute,
            crossoverHighMute = crossoverHighMute,
            
            crossoverSubSolo = crossoverSubSolo,
            crossoverLowSolo = crossoverLowSolo,
            crossoverMidSolo = crossoverMidSolo,
            crossoverHighSolo = crossoverHighSolo,
            
            crossoverSubInvert = crossoverSubInvert,
            crossoverLowInvert = crossoverLowInvert,
            crossoverMidInvert = crossoverMidInvert,
            crossoverHighInvert = crossoverHighInvert,
            
            limiterThresholdDb = limiterThresholdDb,
            limiterReleaseMs = limiterReleaseMs,
            limiterCeilingDb = limiterCeilingDb,
            limiterKneeDb = limiterKneeDb,
            isLimiterEnabled = isLimiterEnabled,
            
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
