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
