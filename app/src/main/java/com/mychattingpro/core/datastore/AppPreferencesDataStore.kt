package com.mychattingpro.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appPrefsDataStore by preferencesDataStore(name = "mcp_app_preferences")

data class AppPreferences(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val backgroundSyncEnabled: Boolean = false,
    val recentCountryIsoCodes: List<String> = listOf("BD", "US", "GB"),
    val lastRealtimeEventMs: Long = 0L
)

class AppPreferencesDataStore(private val context: Context) {

    private val keyLanguage = stringPreferencesKey("language_code")
    private val keyTheme = stringPreferencesKey("theme_preference")
    private val keyBgSync = booleanPreferencesKey("background_sync_enabled")
    private val keyRecentCountries = stringPreferencesKey("recent_country_isos")
    private val keyLastRealtimeMs = longPreferencesKey("last_realtime_event_ms")

    val preferencesFlow: Flow<AppPreferences> = context.appPrefsDataStore.data.map { prefs ->
        val lang = AppLanguage.fromCode(prefs[keyLanguage])
        val theme = when (prefs[keyTheme]) {
            ThemePreference.LIGHT.name -> ThemePreference.LIGHT
            ThemePreference.DARK.name -> ThemePreference.DARK
            else -> ThemePreference.SYSTEM
        }
        val recent = prefs[keyRecentCountries]
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("BD", "US", "GB")

        AppPreferences(
            language = lang,
            themePreference = theme,
            backgroundSyncEnabled = prefs[keyBgSync] ?: false,
            recentCountryIsoCodes = recent,
            lastRealtimeEventMs = prefs[keyLastRealtimeMs] ?: 0L
        )
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.appPrefsDataStore.edit { it[keyLanguage] = language.code }
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        context.appPrefsDataStore.edit { it[keyTheme] = theme.name }
    }

    suspend fun setBackgroundSyncEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyBgSync] = enabled }
    }

    suspend fun recordRecentCountry(iso2: String) {
        context.appPrefsDataStore.edit { prefs ->
            val current = prefs[keyRecentCountries]?.split(",")?.filter { it.isNotBlank() } ?: listOf("BD", "US", "GB")
            val updated = (listOf(iso2.uppercase()) + current.filterNot { it.equals(iso2, ignoreCase = true) }).take(5)
            prefs[keyRecentCountries] = updated.joinToString(",")
        }
    }

    suspend fun updateLastRealtimeEventMs(timestampMs: Long) {
        context.appPrefsDataStore.edit { prefs ->
            val existing = prefs[keyLastRealtimeMs] ?: 0L
            if (timestampMs > existing) {
                prefs[keyLastRealtimeMs] = timestampMs
            }
        }
    }

    suspend fun getLastRealtimeEventMs(): Long = preferencesFlow.first().lastRealtimeEventMs
}
