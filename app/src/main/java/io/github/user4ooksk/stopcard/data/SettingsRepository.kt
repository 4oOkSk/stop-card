package io.github.user4ooksk.stopcard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.user4ooksk.stopcard.model.AppSettings
import io.github.user4ooksk.stopcard.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsRepository(context: Context) {
    private val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[ONBOARDED] = next.onboarded
            prefs[ENABLED] = next.protectionEnabled
            prefs[OBSERVE_ONLY] = next.observeOnly
            prefs[REVIEW_MS] = next.reviewMs
            prefs[RECENT_VISIBLE_SECONDS] = next.recentVisibleSeconds
            prefs[EXTRA_KEEP_ALIVE] = next.extraKeepAlive
            prefs[SHOW_SYSTEM_APPS] = next.showSystemApps
            prefs[THEME_MODE] = next.themeMode.name
            prefs[DYNAMIC_COLOR] = next.dynamicColor
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            onboarded = this[ONBOARDED] ?: defaults.onboarded,
            protectionEnabled = this[ENABLED] ?: defaults.protectionEnabled,
            observeOnly = this[OBSERVE_ONLY] ?: defaults.observeOnly,
            reviewMs = this[REVIEW_MS] ?: defaults.reviewMs,
            recentVisibleSeconds = this[RECENT_VISIBLE_SECONDS] ?: defaults.recentVisibleSeconds,
            extraKeepAlive = this[EXTRA_KEEP_ALIVE] ?: defaults.extraKeepAlive,
            showSystemApps = this[SHOW_SYSTEM_APPS] ?: defaults.showSystemApps,
            themeMode = this[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.themeMode,
            dynamicColor = this[DYNAMIC_COLOR] ?: defaults.dynamicColor,
        )
    }

    private companion object {
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val ENABLED = booleanPreferencesKey("protection_enabled")
        val OBSERVE_ONLY = booleanPreferencesKey("observe_only")
        val REVIEW_MS = longPreferencesKey("review_ms")
        val RECENT_VISIBLE_SECONDS = intPreferencesKey("recent_visible_seconds")
        val EXTRA_KEEP_ALIVE = booleanPreferencesKey("extra_keep_alive")
        val SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }
}
