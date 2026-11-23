package com.kupalvpn.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads versioned, non-secret profile templates from the Kupal VPN GitHub repository.
 * Server credentials are deliberately not stored in this public catalog.
 */
object RemoteConfigStore {
    const val CONFIG_URL = "https://raw.githubusercontent.com/darwindiolimbo-crypto/kupal-vpn/main/config/kupalvpn-config.json"
    const val EDIT_URL = "https://github.com/darwindiolimbo-crypto/kupal-vpn/edit/main/config/kupalvpn-config.json"
    private const val PREFS = "kupal_remote_config"
    private const val KEY_JSON = "cached_config_json"
    private const val MAX_BYTES = 512 * 1024

    private const val FALLBACK = """{
      "schemaVersion": 1,
      "configVersion": "bundled",
      "payloadTemplates": [],
      "dnsPresets": [],
      "servers": [],
      "xrayProfiles": []
    }"""

    fun read(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: FALLBACK
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject(FALLBACK) }
    }

    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "KupalVPN-Android")
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("GitHub returned HTTP $code")
            val body = connection.inputStream.use { input ->
                val bytes = input.readNBytes(MAX_BYTES + 1)
                require(bytes.size <= MAX_BYTES) { "Remote config is larger than 512 KB" }
                bytes.toString(Charsets.UTF_8)
            }
            val parsed = JSONObject(body)
            require(parsed.optInt("schemaVersion", -1) == 1) { "Unsupported config schema version" }
            require(parsed.has("payloadTemplates") && parsed.get("payloadTemplates") is org.json.JSONArray) {
                "Remote config has no payload template catalog"
            }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_JSON, parsed.toString()).apply()
            "Updated to config ${parsed.optString("configVersion", "latest")}."
        } catch (error: Exception) {
            throw IllegalStateException("Config update failed: ${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            connection?.disconnect()
        }
    }
}
