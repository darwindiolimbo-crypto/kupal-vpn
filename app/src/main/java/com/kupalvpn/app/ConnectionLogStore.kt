package com.kupalvpn.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ConnectionLogEntry(val timestamp: Long, val state: String, val message: String)

object ConnectionLogStore {
    private const val PREFS = "kupal_vpn_connection_logs"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 250

    @Synchronized
    fun add(context: Context, state: String, message: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString(KEY_ENTRIES, "[]")) }.getOrDefault(JSONArray())
        val next = JSONArray()
        val start = (old.length() - MAX_ENTRIES + 1).coerceAtLeast(0)
        for (i in start until old.length()) next.put(old.optJSONObject(i) ?: JSONObject())
        next.put(JSONObject().put("timestamp", System.currentTimeMillis()).put("state", state).put("message", message))
        prefs.edit().putString(KEY_ENTRIES, next.toString()).apply()
    }

    fun read(context: Context): List<ConnectionLogEntry> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENTRIES, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            ConnectionLogEntry(item.optLong("timestamp"), item.optString("state", "INFO"), item.optString("message"))
        }.reversed()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ENTRIES).apply()
    }
}