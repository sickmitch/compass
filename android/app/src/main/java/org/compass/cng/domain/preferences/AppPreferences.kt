package org.compass.cng.domain.preferences

enum class AppThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppPreferences(
    val theme: AppThemePreference = AppThemePreference.SYSTEM,
    val voiceGuidanceDefault: Boolean = true,
    val highwaysEnabled: Boolean = true,
)

interface AppPreferencesRepository {
    fun load(): AppPreferences

    fun save(value: AppPreferences): AppPreferences
}

class InMemoryAppPreferencesRepository(
    initial: AppPreferences = AppPreferences(),
) : AppPreferencesRepository {
    private var value = initial

    override fun load(): AppPreferences = value

    override fun save(value: AppPreferences): AppPreferences = value.also { this.value = it }
}
