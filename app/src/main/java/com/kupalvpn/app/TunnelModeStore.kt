package com.kupalvpn.app

import android.content.Context

object TunnelModeStore {
    const val MODE_XRAY = "XRAY"
    const val MODE_HTTP = "HTTP"
    const val MODE_SSH = "SSH"
    private const val PREFS = "kupal_tunnel_mode"

    private fun key(context: Context, base: String) = "${base}_${ProfileStore.activeProfileId(context)}"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readMode(context: Context): String {
        val saved = prefs(context).getString(key(context, "mode"), MODE_XRAY) ?: MODE_XRAY
        // Legacy HTTP mode is now an SSH payload transport setting.
        if (saved == MODE_HTTP) {
            saveMode(context, MODE_SSH)
            return MODE_SSH
        }
        return if (saved == MODE_SSH) MODE_SSH else MODE_XRAY
    }

    fun saveMode(context: Context, mode: String) {
        require(mode == MODE_XRAY || mode == MODE_SSH) { "Unsupported tunnel mode: $mode" }
        prefs(context).edit().putString(key(context, "mode"), mode).apply()
    }
}
