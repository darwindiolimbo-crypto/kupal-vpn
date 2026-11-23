package com.kupalvpn.app

import android.content.Context

object LocalStore {
    private const val FILE = "kupal_vpn_settings"
    private const val KEY_JSON = "xray_json_draft"
    private const val KEY_PAYLOAD = "payload_template"
    private const val KEY_ADS_ENABLED = "ads_timer_enabled"
    private const val KEY_AD_CYCLE_START = "ads_timer_cycle_start"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun readJson(context: Context): String = prefs(context).getString(KEY_JSON, DEFAULT_JSON) ?: DEFAULT_JSON
    fun writeJson(context: Context, value: String) = prefs(context).edit().putString(KEY_JSON, value).apply()

    fun readPayload(context: Context): String = prefs(context).getString(KEY_PAYLOAD, DEFAULT_PAYLOAD) ?: DEFAULT_PAYLOAD
    fun writePayload(context: Context, value: String) = prefs(context).edit().putString(KEY_PAYLOAD, value).apply()

    fun adsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ADS_ENABLED, true)
    fun setAdsEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ADS_ENABLED, enabled).apply()

    const val AD_REFRESH_INTERVAL_SECONDS = 10L

    fun adCycleStart(context: Context): Long {
        val preferences = prefs(context)
        val saved = preferences.getLong(KEY_AD_CYCLE_START, 0L)
        if (saved != 0L) return saved
        val now = System.currentTimeMillis()
        preferences.edit().putLong(KEY_AD_CYCLE_START, now).apply()
        return now
    }

    fun resetAdCycle(context: Context) {
        prefs(context).edit().putLong(KEY_AD_CYCLE_START, System.currentTimeMillis()).apply()
    }

    private const val DEFAULT_JSON = """{
  "log": { "loglevel": "warning" },
  "inbounds": [],
  "outbounds": [
    {
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "server.example.com",
            "port": 443,
            "users": [
              { "id": "YOUR-UUID-HERE", "encryption": "none" }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "tcp",
        "security": "tls"
      }
    }
  ]
}"""

    private const val DEFAULT_PAYLOAD = """CONNECT [host_port] HTTP/1.1[crlf]
Host: [host][crlf]
User-Agent: KupalVPN/1.0[crlf]
Proxy-Connection: keep-alive[crlf][crlf]"""
}
