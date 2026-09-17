package ru.vvsu.schedule

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(
    name = "timetable_settings"
)

class AppPreferences(
    private val context: Context
) {

    private val groupKey =
        stringPreferencesKey("selected_group")

    private val teacherKey =
        stringPreferencesKey("selected_teacher")

    private val darkThemeKey =
        booleanPreferencesKey("dark_theme")

    private val autoRefreshKey =
        booleanPreferencesKey("auto_refresh")

    private val notificationsKey =
        booleanPreferencesKey("notifications")

    private val colorKey =
        stringPreferencesKey("theme_color")

    val settings: Flow<Settings> =
        context.dataStore.data.map { preferences ->

            Settings(
                group = preferences[groupKey].orEmpty(),

                teacher = preferences[teacherKey].orEmpty(),

                darkTheme =
                    preferences[darkThemeKey] ?: false,

                autoRefresh =
                    preferences[autoRefreshKey] ?: true,

                notifications =
                    preferences[notificationsKey] ?: true,

                color =
                    preferences[colorKey] ?: "blue"
            )
        }
}

data class Settings(
    val group: String,
    val teacher: String,
    val darkTheme: Boolean,
    val autoRefresh: Boolean,
    val notifications: Boolean,
    val color: String
)
