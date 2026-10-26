package dev.narze.pood

import android.content.Context
import androidx.core.content.edit

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("pood", Context.MODE_PRIVATE)

    /** Gateway base URL, e.g. https://server.tailnet.ts.net:8790 (no trailing slash). */
    var serverUrl: String
        get() = prefs.getString("serverUrl", "")!!
        set(value) = prefs.edit { putString("serverUrl", value.trim().trimEnd('/')) }

    var autoCopy: Boolean
        get() = prefs.getBoolean("autoCopy", true)
        set(value) = prefs.edit { putBoolean("autoCopy", value) }

    /** Send every final transcript to [beamTargets] as soon as it arrives. Swiping a transcript beams it either way. */
    var autoBeam: Boolean
        get() = prefs.getBoolean("autoBeam", true)
        set(value) = prefs.edit { putBoolean("autoBeam", value) }

    /** Names of the desktops that receive beamed transcripts. Empty means none: nothing is sent anywhere. */
    var beamTargets: Set<String>
        get() = prefs.getStringSet("beamTargets", emptySet())!!.toSet()
        set(value) = prefs.edit { putStringSet("beamTargets", value) }

    var bubbleX: Int
        get() = prefs.getInt("bubbleX", -1)
        set(value) = prefs.edit { putInt("bubbleX", value) }

    var bubbleY: Int
        get() = prefs.getInt("bubbleY", 400)
        set(value) = prefs.edit { putInt("bubbleY", value) }
}
