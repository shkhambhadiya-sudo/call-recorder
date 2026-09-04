package com.sanket.callrecorder

import android.content.Context

/** Small wrapper around SharedPreferences for user settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Master switch: auto-record every call. */
    var autoRecord: Boolean
        get() = sp.getBoolean("auto_record", true)
        set(v) = sp.edit().putBoolean("auto_record", v).apply()

    /** AAC bitrate in bits/sec. Lower = smaller files. Default 32 kbps (voice). */
    var bitrate: Int
        get() = sp.getInt("bitrate", 32000)
        set(v) = sp.edit().putInt("bitrate", v).apply()

    /** Check GitHub for updates automatically on launch. */
    var autoUpdateCheck: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()
}
