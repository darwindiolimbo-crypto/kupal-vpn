package com.kupalvpn.app

import android.content.Context

object LocalStore {
    private const val FILE = "kupal_vpn_settings"
    private const val KEY_JSON = "xray_json_draft"
    private const val KEY_PAYLOAD = "payload_template"
    private const val KEY_ADS_ENABLED = "ads_timer_enabled"
    private const val KEY_AD_LAST_SHOWN = "ads_last_shown"
    private const val KEY_VPN_TIME_BALANCE = "vpn_time_balance_ms"
    private const val KEY_VPN_TIME_STARTED = "vpn_time_started_at"
    private const val KEY_FIRST_RUN_INITIALIZED = "first_run_time_seeded"
    private const val KEY_STARTER_TIME_RECOVERY_V2 = "starter_time_recovery_v2"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun profileKey(base: String, context: Context): String =
        "${base}_${ProfileStore.activeProfileId(context)}"

    fun readJson(context: Context): String =
        prefs(context).getString(profileKey(KEY_JSON, context), null) ?: prefs(context).getString(KEY_JSON, DEFAULT_JSON) ?: DEFAULT_JSON
    fun writeJson(context: Context, value: String) =
        prefs(context).edit().putString(profileKey(KEY_JSON, context), value).apply()

    fun readPayload(context: Context): String =
        prefs(context).getString(profileKey(KEY_PAYLOAD, context), null) ?: prefs(context).getString(KEY_PAYLOAD, DEFAULT_PAYLOAD) ?: DEFAULT_PAYLOAD
    fun writePayload(context: Context, value: String) =
        prefs(context).edit().putString(profileKey(KEY_PAYLOAD, context), value).apply()

    fun adsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ADS_ENABLED, true)
    fun setAdsEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ADS_ENABLED, enabled).apply()

    /**
     * Grants the initial two-hour balance once when no VPN-time history exists.
     * This also covers fresh installs that receive an app update before first launch.
     */
    private val firstRunLock = Any()
    private val vpnTimeLock = Any()

    fun initializeFirstRun(context: Context) {
        synchronized(firstRunLock) {
            val p = prefs(context)
            if (p.getBoolean(KEY_STARTER_TIME_RECOVERY_V2, false)) return

            val currentBalance = p.getLong(KEY_VPN_TIME_BALANCE, 0L).coerceAtLeast(0L)
            val editor = p.edit()
                .putBoolean(KEY_FIRST_RUN_INITIALIZED, true)
                .putBoolean(KEY_STARTER_TIME_RECOVERY_V2, true)

            // Recover installs that were left with a zero balance by an earlier
            // build. Clear a stale start marker so the new grant is not consumed.
            if (currentBalance <= 0L) {
                editor.putLong(KEY_VPN_TIME_BALANCE, VPN_REWARD_MILLIS)
                    .remove(KEY_VPN_TIME_STARTED)
            }
            check(editor.commit()) { "Could not save initial VPN time" }
        }
    }

    const val AD_COOLDOWN_SECONDS = 10L
    const val VPN_REWARD_MILLIS = 2L * 60L * 60L * 1000L

    fun remainingAdCooldownMillis(context: Context): Long {
        val lastShown = prefs(context).getLong(KEY_AD_LAST_SHOWN, 0L)
        if (lastShown == 0L) return 0L
        return (lastShown + AD_COOLDOWN_SECONDS * 1000L - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun markAdShown(context: Context) {
        prefs(context).edit().putLong(KEY_AD_LAST_SHOWN, System.currentTimeMillis()).apply()
    }

    fun remainingVpnMillis(context: Context): Long {
        val p = prefs(context)
        val balance = p.getLong(KEY_VPN_TIME_BALANCE, 0L).coerceAtLeast(0L)
        val started = p.getLong(KEY_VPN_TIME_STARTED, 0L)
        return if (started == 0L) balance else (balance - (System.currentTimeMillis() - started)).coerceAtLeast(0L)
    }

    fun grantVpnTime(context: Context, millis: Long = VPN_REWARD_MILLIS) {
        require(millis > 0L) { "VPN time grant must be positive" }
        synchronized(vpnTimeLock) {
            val p = prefs(context)
            val started = p.getLong(KEY_VPN_TIME_STARTED, 0L)
            val balance = remainingVpnMillis(context)
            val editor = p.edit().putLong(KEY_VPN_TIME_BALANCE, balance + millis)
            if (started != 0L) editor.putLong(KEY_VPN_TIME_STARTED, System.currentTimeMillis())
            check(editor.commit()) { "Could not save rewarded VPN time" }
        }
    }

    fun startVpnTime(context: Context): Boolean = synchronized(vpnTimeLock) {
        val p = prefs(context)
        if (remainingVpnMillis(context) <= 0L) return@synchronized false
        if (p.getLong(KEY_VPN_TIME_STARTED, 0L) == 0L) {
            check(p.edit().putLong(KEY_VPN_TIME_STARTED, System.currentTimeMillis()).commit()) {
                "Could not start VPN time accounting"
            }
        }
        true
    }

    fun pauseVpnTime(context: Context) {
        synchronized(vpnTimeLock) {
            val p = prefs(context)
            val started = p.getLong(KEY_VPN_TIME_STARTED, 0L)
            if (started != 0L) {
                val remaining = remainingVpnMillis(context)
                check(p.edit().putLong(KEY_VPN_TIME_BALANCE, remaining)
                    .remove(KEY_VPN_TIME_STARTED).commit()) {
                    "Could not save remaining VPN time"
                }
            }
        }
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
