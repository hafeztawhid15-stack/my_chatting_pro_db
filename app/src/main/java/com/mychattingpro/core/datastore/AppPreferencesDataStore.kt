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
    val recentCountryIsoCodes: List<String> = listOf("BD", "US", "GB"),
    val lastRealtimeEventMs: Long = 0L,
    val recoveryCodeConfigured: Boolean = false,
    // 14 Notification & Delivery Preferences
    val notificationsEnabled: Boolean = true,
    val messageNotificationsEnabled: Boolean = true,
    val privateChatNotificationsEnabled: Boolean = true,
    val showMessagePreview: Boolean = true,
    val messageSoundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val inAppAlertsEnabled: Boolean = true,
    val callNotificationsEnabled: Boolean = true,
    val missedCallNotificationsEnabled: Boolean = true,
    val callVibrationEnabled: Boolean = true,
    val securityAlertsEnabled: Boolean = true,
    val accountAlertsEnabled: Boolean = true,
    val backgroundSyncEnabled: Boolean = true,
    val unifiedPushEnabled: Boolean = false,
    val unifiedPushEndpointUrl: String = ""
)

class AppPreferencesDataStore(private val context: Context) {

    private val keyLanguage = stringPreferencesKey("language_code")
    private val keyTheme = stringPreferencesKey("theme_preference")
    private val keyRecentCountries = stringPreferencesKey("recent_country_isos")
    private val keyLastRealtimeMs = longPreferencesKey("last_realtime_event_ms")
    private val keyRecoveryCodeConfigured = booleanPreferencesKey("recovery_code_configured")

    private val keyNotificationsEnabled = booleanPreferencesKey("notif_master_enabled")
    private val keyMessageNotifications = booleanPreferencesKey("notif_messages_enabled")
    private val keyPrivateChatNotifications = booleanPreferencesKey("notif_private_chat_enabled")
    private val keyShowMessagePreview = booleanPreferencesKey("notif_show_preview")
    private val keyMessageSoundEnabled = booleanPreferencesKey("notif_message_sound_enabled")
    private val keyVibrationEnabled = booleanPreferencesKey("notif_vibration_enabled")
    private val keyInAppAlertsEnabled = booleanPreferencesKey("notif_in_app_alerts_enabled")
    private val keyCallNotifications = booleanPreferencesKey("notif_calls_enabled")
    private val keyMissedCallNotifications = booleanPreferencesKey("notif_missed_calls_enabled")
    private val keyCallVibrationEnabled = booleanPreferencesKey("notif_call_vibration_enabled")
    private val keySecurityAlerts = booleanPreferencesKey("notif_security_alerts_enabled")
    private val keyAccountAlerts = booleanPreferencesKey("notif_account_alerts_enabled")
    private val keyBgSync = booleanPreferencesKey("background_sync_enabled")
    private val keyUnifiedPushEnabled = booleanPreferencesKey("notif_unified_push_enabled")
    private val keyUnifiedPushUrl = stringPreferencesKey("notif_unified_push_url")

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
            recentCountryIsoCodes = recent,
            lastRealtimeEventMs = prefs[keyLastRealtimeMs] ?: 0L,
            recoveryCodeConfigured = prefs[keyRecoveryCodeConfigured] ?: false,
            notificationsEnabled = prefs[keyNotificationsEnabled] ?: true,
            messageNotificationsEnabled = prefs[keyMessageNotifications] ?: true,
            privateChatNotificationsEnabled = prefs[keyPrivateChatNotifications] ?: true,
            showMessagePreview = prefs[keyShowMessagePreview] ?: true,
            messageSoundEnabled = prefs[keyMessageSoundEnabled] ?: true,
            vibrationEnabled = prefs[keyVibrationEnabled] ?: true,
            inAppAlertsEnabled = prefs[keyInAppAlertsEnabled] ?: true,
            callNotificationsEnabled = prefs[keyCallNotifications] ?: true,
            missedCallNotificationsEnabled = prefs[keyMissedCallNotifications] ?: true,
            callVibrationEnabled = prefs[keyCallVibrationEnabled] ?: true,
            securityAlertsEnabled = prefs[keySecurityAlerts] ?: true,
            accountAlertsEnabled = prefs[keyAccountAlerts] ?: true,
            backgroundSyncEnabled = prefs[keyBgSync] ?: true,
            unifiedPushEnabled = prefs[keyUnifiedPushEnabled] ?: false,
            unifiedPushEndpointUrl = prefs[keyUnifiedPushUrl].orEmpty()
        )
    }

    suspend fun current(): AppPreferences = preferencesFlow.first()

    suspend fun setLanguage(language: AppLanguage) {
        context.appPrefsDataStore.edit { it[keyLanguage] = language.code }
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        context.appPrefsDataStore.edit { it[keyTheme] = theme.name }
    }

    suspend fun setRecoveryCodeConfigured(configured: Boolean) {
        context.appPrefsDataStore.edit { it[keyRecoveryCodeConfigured] = configured }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyNotificationsEnabled] = enabled }
    }

    suspend fun setMessageNotificationsEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyMessageNotifications] = enabled }
    }

    suspend fun setPrivateChatNotificationsEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyPrivateChatNotifications] = enabled }
    }

    suspend fun setShowMessagePreview(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyShowMessagePreview] = enabled }
    }

    suspend fun setMessageSoundEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyMessageSoundEnabled] = enabled }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyVibrationEnabled] = enabled }
    }

    suspend fun setInAppAlertsEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyInAppAlertsEnabled] = enabled }
    }

    suspend fun setCallNotificationsEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyCallNotifications] = enabled }
    }

    suspend fun setMissedCallNotificationsEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyMissedCallNotifications] = enabled }
    }

    suspend fun setCallVibrationEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyCallVibrationEnabled] = enabled }
    }

    suspend fun setSecurityAlertsEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keySecurityAlerts] = enabled }
    }

    suspend fun setAccountAlertsEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyAccountAlerts] = enabled }
    }

    suspend fun setBackgroundSyncEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[keyBgSync] = enabled }
    }

    suspend fun setUnifiedPushConfig(enabled: Boolean, endpointUrl: String) {
        context.appPrefsDataStore.edit {
            it[keyUnifiedPushEnabled] = enabled
            it[keyUnifiedPushUrl] = endpointUrl.trim()
        }
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
