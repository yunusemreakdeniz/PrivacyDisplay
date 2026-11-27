package io.github.yunusemreakdeniz.privacydisplay

import android.content.Context

/** User settings, kept in plain SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("privacy_display", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Shield darkness, 0.5 to 0.8. Android lets touches through an overlay only up to 0.8. */
    var intensity: Float
        get() = sp.getFloat("intensity", 0.8f)
        set(v) = sp.edit().putFloat("intensity", v.coerceIn(0.5f, 0.8f)).apply()

    /** true: watch only in the chosen apps; false: everywhere while the screen is on. */
    var onlySelectedApps: Boolean
        get() = sp.getBoolean("only_selected", false)
        set(v) = sp.edit().putBoolean("only_selected", v).apply()

    var selectedApps: Set<String>
        get() = sp.getStringSet("apps", emptySet()).orEmpty()
        set(v) = sp.edit().putStringSet("apps", v).apply()

    /** Ghost mode: a permanent privacy-filter look (dark tint and fine lines), no camera needed. */
    var ghostMode: Boolean
        get() = sp.getBoolean("ghost", true)
        set(v) = sp.edit().putBoolean("ghost", v).apply()

    /** Ghost filter strength, 0.3 to 0.9 of the allowed darkness. */
    var ghostStrength: Float
        get() = sp.getFloat("ghost_strength", 0.6f)
        set(v) = sp.edit().putFloat("ghost_strength", v.coerceIn(0.3f, 0.9f)).apply()

    /** Smart protection: the front camera spots a second person and drops the full curtain. */
    var smartMode: Boolean
        get() = sp.getBoolean("smart", true)
        set(v) = sp.edit().putBoolean("smart", v).apply()

    /** Battery saver: the camera sleeps while the phone is still and wakes on a move or a timer. */
    var batterySaver: Boolean
        get() = sp.getBoolean("battery_saver", false)
        set(v) = sp.edit().putBoolean("battery_saver", v).apply()

    /** How often the camera wakes for a check while the phone lies still, in seconds. */
    var idleCheckSeconds: Int
        get() = sp.getInt("idle_check", 5)
        set(v) = sp.edit().putInt("idle_check", v.coerceIn(2, 30)).apply()

    /** Shield when nobody is looking at the screen (no face). */
    var shieldWhenNoFace: Boolean
        get() = sp.getBoolean("no_face_v2", false)
        set(v) = sp.edit().putBoolean("no_face_v2", v).apply()
}
