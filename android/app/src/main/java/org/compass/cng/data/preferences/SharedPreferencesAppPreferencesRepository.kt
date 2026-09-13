package org.compass.cng.data.preferences

import android.content.Context
import org.compass.cng.domain.preferences.AppPreferences
import org.compass.cng.domain.preferences.AppPreferencesRepository
import org.compass.cng.domain.preferences.AppThemePreference

class SharedPreferencesAppPreferencesRepository(context: Context) : AppPreferencesRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): AppPreferences = AppPreferences(
        theme = preferences.getString(THEME_KEY, null)
            ?.let { stored -> AppThemePreference.entries.firstOrNull { it.name == stored } }
            ?: AppThemePreference.SYSTEM,
        voiceGuidanceDefault = preferences.getBoolean(VOICE_DEFAULT_KEY, true),
    )

    override fun save(value: AppPreferences): AppPreferences {
        check(
            preferences.edit()
                .putString(THEME_KEY, value.theme.name)
                .putBoolean(VOICE_DEFAULT_KEY, value.voiceGuidanceDefault)
                .commit(),
        ) { "app preferences could not be persisted" }
        return value
    }

    private companion object {
        const val PREFERENCES_NAME = "compass_app_preferences"
        const val THEME_KEY = "theme_v1"
        const val VOICE_DEFAULT_KEY = "voice_guidance_default_v1"
    }
}
