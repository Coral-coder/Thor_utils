package gg.thor.utils.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "thor_utils")

/** Where the HUD sits on its display. */
enum class HudPosition { TOP, BOTTOM, LEFT, RIGHT, FLOATING }

/** Everything the user can tune, persisted. */
data class ThorSettings(
    /** Base hue for the glass theme, 0..360. */
    val accentHue: Float = 205f,
    /** Saturation of the glass tint, 0..1. */
    val accentSaturation: Float = 0.72f,
    /** Panel translucency, 0..1 — higher is more opaque. */
    val glassOpacity: Float = 0.55f,
    /** Whether to blur what is behind the glass (needs Android 12+). */
    val glassBlur: Boolean = true,

    /** Battery service running. */
    val batteryMonitorEnabled: Boolean = true,
    /** Seconds between battery samples. */
    val batterySampleSeconds: Int = 20,
    /** Battery pack capacity in µAh; 0 means "ask the system". */
    val batteryCapacityUah: Long = 0,

    /** Stats HUD running. */
    val hudEnabled: Boolean = false,
    /** Display the HUD is pinned to. -1 means the secondary display if present. */
    val hudDisplayId: Int = -1,
    val hudPosition: HudPosition = HudPosition.BOTTOM,
    val hudScale: Float = 1f,
    val hudShowCpu: Boolean = true,
    val hudShowGpu: Boolean = true,
    val hudShowMemory: Boolean = true,
    val hudShowBattery: Boolean = true,
    val hudShowTemperature: Boolean = true,
    val hudShowStorage: Boolean = true,

    /**
     * Display that should hold input focus. When set, touching the HUD hands
     * focus straight back to this display instead of leaving it on the panel the
     * user just tapped.
     */
    val focusPriorityDisplayId: Int = 0,
    val focusReturnEnabled: Boolean = true,

    /** ROMs root for the sorter, e.g. /storage/emulated/0/ROMs. */
    val romsRoot: String = "",
    val lastSortSource: String = "",
)

/** Typed accessor over DataStore. */
class SettingsRepository(private val context: Context) {

    val settings: Flow<ThorSettings> = context.dataStore.data.map { prefs ->
        ThorSettings(
            accentHue = prefs[Keys.ACCENT_HUE] ?: 205f,
            accentSaturation = prefs[Keys.ACCENT_SATURATION] ?: 0.72f,
            glassOpacity = prefs[Keys.GLASS_OPACITY] ?: 0.55f,
            glassBlur = prefs[Keys.GLASS_BLUR] ?: true,
            batteryMonitorEnabled = prefs[Keys.BATTERY_ENABLED] ?: true,
            batterySampleSeconds = prefs[Keys.BATTERY_SAMPLE_SECONDS] ?: 20,
            batteryCapacityUah = prefs[Keys.BATTERY_CAPACITY]?.toLong() ?: 0L,
            hudEnabled = prefs[Keys.HUD_ENABLED] ?: false,
            hudDisplayId = prefs[Keys.HUD_DISPLAY_ID] ?: -1,
            hudPosition = prefs[Keys.HUD_POSITION]?.let { runCatching { HudPosition.valueOf(it) }.getOrNull() }
                ?: HudPosition.BOTTOM,
            hudScale = prefs[Keys.HUD_SCALE] ?: 1f,
            hudShowCpu = prefs[Keys.HUD_CPU] ?: true,
            hudShowGpu = prefs[Keys.HUD_GPU] ?: true,
            hudShowMemory = prefs[Keys.HUD_MEMORY] ?: true,
            hudShowBattery = prefs[Keys.HUD_BATTERY] ?: true,
            hudShowTemperature = prefs[Keys.HUD_TEMPERATURE] ?: true,
            hudShowStorage = prefs[Keys.HUD_STORAGE] ?: true,
            focusPriorityDisplayId = prefs[Keys.FOCUS_DISPLAY_ID] ?: 0,
            focusReturnEnabled = prefs[Keys.FOCUS_RETURN] ?: true,
            romsRoot = prefs[Keys.ROMS_ROOT] ?: "",
            lastSortSource = prefs[Keys.LAST_SORT_SOURCE] ?: "",
        )
    }

    suspend fun update(transform: (MutablePrefs) -> Unit) {
        context.dataStore.edit { prefs -> transform(MutablePrefs(prefs)) }
    }

    /** Thin typed wrapper so callers never touch raw preference keys. */
    class MutablePrefs(private val prefs: androidx.datastore.preferences.core.MutablePreferences) {
        var accentHue: Float
            get() = prefs[Keys.ACCENT_HUE] ?: 205f
            set(value) { prefs[Keys.ACCENT_HUE] = value.coerceIn(0f, 360f) }
        var accentSaturation: Float
            get() = prefs[Keys.ACCENT_SATURATION] ?: 0.72f
            set(value) { prefs[Keys.ACCENT_SATURATION] = value.coerceIn(0f, 1f) }
        var glassOpacity: Float
            get() = prefs[Keys.GLASS_OPACITY] ?: 0.55f
            set(value) { prefs[Keys.GLASS_OPACITY] = value.coerceIn(0.15f, 1f) }
        var glassBlur: Boolean
            get() = prefs[Keys.GLASS_BLUR] ?: true
            set(value) { prefs[Keys.GLASS_BLUR] = value }
        var batteryMonitorEnabled: Boolean
            get() = prefs[Keys.BATTERY_ENABLED] ?: true
            set(value) { prefs[Keys.BATTERY_ENABLED] = value }
        var batterySampleSeconds: Int
            get() = prefs[Keys.BATTERY_SAMPLE_SECONDS] ?: 20
            set(value) { prefs[Keys.BATTERY_SAMPLE_SECONDS] = value.coerceIn(5, 300) }
        var batteryCapacityUah: Long
            get() = prefs[Keys.BATTERY_CAPACITY]?.toLong() ?: 0L
            set(value) { prefs[Keys.BATTERY_CAPACITY] = value.toInt() }
        var hudEnabled: Boolean
            get() = prefs[Keys.HUD_ENABLED] ?: false
            set(value) { prefs[Keys.HUD_ENABLED] = value }
        var hudDisplayId: Int
            get() = prefs[Keys.HUD_DISPLAY_ID] ?: -1
            set(value) { prefs[Keys.HUD_DISPLAY_ID] = value }
        var hudPosition: HudPosition
            get() = prefs[Keys.HUD_POSITION]?.let { runCatching { HudPosition.valueOf(it) }.getOrNull() }
                ?: HudPosition.BOTTOM
            set(value) { prefs[Keys.HUD_POSITION] = value.name }
        var hudScale: Float
            get() = prefs[Keys.HUD_SCALE] ?: 1f
            set(value) { prefs[Keys.HUD_SCALE] = value.coerceIn(0.6f, 2f) }
        var hudShowCpu: Boolean
            get() = prefs[Keys.HUD_CPU] ?: true
            set(value) { prefs[Keys.HUD_CPU] = value }
        var hudShowGpu: Boolean
            get() = prefs[Keys.HUD_GPU] ?: true
            set(value) { prefs[Keys.HUD_GPU] = value }
        var hudShowMemory: Boolean
            get() = prefs[Keys.HUD_MEMORY] ?: true
            set(value) { prefs[Keys.HUD_MEMORY] = value }
        var hudShowBattery: Boolean
            get() = prefs[Keys.HUD_BATTERY] ?: true
            set(value) { prefs[Keys.HUD_BATTERY] = value }
        var hudShowTemperature: Boolean
            get() = prefs[Keys.HUD_TEMPERATURE] ?: true
            set(value) { prefs[Keys.HUD_TEMPERATURE] = value }
        var hudShowStorage: Boolean
            get() = prefs[Keys.HUD_STORAGE] ?: true
            set(value) { prefs[Keys.HUD_STORAGE] = value }
        var focusPriorityDisplayId: Int
            get() = prefs[Keys.FOCUS_DISPLAY_ID] ?: 0
            set(value) { prefs[Keys.FOCUS_DISPLAY_ID] = value }
        var focusReturnEnabled: Boolean
            get() = prefs[Keys.FOCUS_RETURN] ?: true
            set(value) { prefs[Keys.FOCUS_RETURN] = value }
        var romsRoot: String
            get() = prefs[Keys.ROMS_ROOT] ?: ""
            set(value) { prefs[Keys.ROMS_ROOT] = value }
        var lastSortSource: String
            get() = prefs[Keys.LAST_SORT_SOURCE] ?: ""
            set(value) { prefs[Keys.LAST_SORT_SOURCE] = value }
    }

    private object Keys {
        val ACCENT_HUE = floatPreferencesKey("accent_hue")
        val ACCENT_SATURATION = floatPreferencesKey("accent_saturation")
        val GLASS_OPACITY = floatPreferencesKey("glass_opacity")
        val GLASS_BLUR = booleanPreferencesKey("glass_blur")
        val BATTERY_ENABLED = booleanPreferencesKey("battery_enabled")
        val BATTERY_SAMPLE_SECONDS = intPreferencesKey("battery_sample_seconds")
        val BATTERY_CAPACITY = intPreferencesKey("battery_capacity_uah")
        val HUD_ENABLED = booleanPreferencesKey("hud_enabled")
        val HUD_DISPLAY_ID = intPreferencesKey("hud_display_id")
        val HUD_POSITION = stringPreferencesKey("hud_position")
        val HUD_SCALE = floatPreferencesKey("hud_scale")
        val HUD_CPU = booleanPreferencesKey("hud_cpu")
        val HUD_GPU = booleanPreferencesKey("hud_gpu")
        val HUD_MEMORY = booleanPreferencesKey("hud_memory")
        val HUD_BATTERY = booleanPreferencesKey("hud_battery")
        val HUD_TEMPERATURE = booleanPreferencesKey("hud_temperature")
        val HUD_STORAGE = booleanPreferencesKey("hud_storage")
        val FOCUS_DISPLAY_ID = intPreferencesKey("focus_display_id")
        val FOCUS_RETURN = booleanPreferencesKey("focus_return")
        val ROMS_ROOT = stringPreferencesKey("roms_root")
        val LAST_SORT_SOURCE = stringPreferencesKey("last_sort_source")
    }
}
