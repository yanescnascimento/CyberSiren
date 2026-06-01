package com.cybersiren.android.nostr

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PoWPreferenceManager {

    private const val PREFS_NAME = "pow_preferences"
    private const val KEY_POW_ENABLED = "pow_enabled"
    private const val KEY_POW_DIFFICULTY = "pow_difficulty"

    private const val DEFAULT_POW_ENABLED = false
    private const val DEFAULT_POW_DIFFICULTY = 12

    private val _powEnabled = MutableStateFlow(DEFAULT_POW_ENABLED)
    val powEnabled: StateFlow<Boolean> = _powEnabled.asStateFlow()

    private val _powDifficulty = MutableStateFlow(DEFAULT_POW_DIFFICULTY)
    val powDifficulty: StateFlow<Int> = _powDifficulty.asStateFlow()

    private val _isMining = MutableStateFlow(false)
    val isMining: StateFlow<Boolean> = _isMining.asStateFlow()

    private lateinit var sharedPrefs: SharedPreferences
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return

        sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        _powEnabled.value = sharedPrefs.getBoolean(KEY_POW_ENABLED, DEFAULT_POW_ENABLED)
        _powDifficulty.value = sharedPrefs.getInt(KEY_POW_DIFFICULTY, DEFAULT_POW_DIFFICULTY)

        isInitialized = true
    }

    fun isPowEnabled(): Boolean {
        return _powEnabled.value
    }

    fun setPowEnabled(enabled: Boolean) {
        _powEnabled.value = enabled
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.edit().putBoolean(KEY_POW_ENABLED, enabled).apply()
        }
    }

    fun getPowDifficulty(): Int {
        return _powDifficulty.value
    }

    fun setPowDifficulty(difficulty: Int) {
        val clampedDifficulty = difficulty.coerceIn(0, 32)
        _powDifficulty.value = clampedDifficulty
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.edit().putInt(KEY_POW_DIFFICULTY, clampedDifficulty).apply()
        }
    }

    data class PoWSettings(
        val enabled: Boolean,
        val difficulty: Int
    )

    fun getCurrentSettings(): PoWSettings {
        return PoWSettings(
            enabled = _powEnabled.value,
            difficulty = _powDifficulty.value
        )
    }

    fun resetToDefaults() {
        setPowEnabled(DEFAULT_POW_ENABLED)
        setPowDifficulty(DEFAULT_POW_DIFFICULTY)
    }

    fun getDifficultyLevels(): List<Pair<Int, String>> {
        return listOf(
            0 to "Disabled (no PoW)",
            8 to "Very Low (instant)",
            12 to "Low (~0.1s)",
            16 to "Medium (~2s)",
            20 to "High (~30s)",
            24 to "Very High (~8m)",
            28 to "Extreme (~2h)",
            32 to "Maximum (~8h)"
        )
    }

    fun isMining(): Boolean {
        return _isMining.value
    }

    fun startMining() {
        _isMining.value = true
    }

    fun stopMining() {
        _isMining.value = false
    }
}
