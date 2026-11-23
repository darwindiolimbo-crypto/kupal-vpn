package com.kupalvpn.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TunnelModeStore {
    const val MODE_XRAY = "XRAY"
    const val MODE_HTTP = "HTTP"
    const val MODE_SSH = "SSH"
    private const val PREFS = "kupal_tunnel_mode"

    private fun key(context: Context, base: String) = "${base}_${ProfileStore.activeProfileId(context)}"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readMode(context: Context) = prefs(context).getString(key(context, "mode"), MODE_XRAY) ?: MODE_XRAY
    fun saveMode(context: Context, mode: String) { prefs(context).edit().putString(key(context, "mode"), mode).apply() }
    fun readHttpHost(context: Context) = prefs(context).getString(key(context, "http_host"), "") ?: ""
    fun saveHttpHost(context: Context, value: String) { prefs(context).edit().putString(key(context, "http_host"), value.trim()).apply() }
    fun readHttpPort(context: Context) = prefs(context).getString(key(context, "http_port"), "8080") ?: "8080"
    fun saveHttpPort(context: Context, value: String) { prefs(context).edit().putString(key(context, "http_port"), value.trim()).apply() }
    fun readHttpUser(context: Context) = prefs(context).getString(key(context, "http_user"), "") ?: ""
    fun saveHttpUser(context: Context, value: String) { prefs(context).edit().putString(key(context, "http_user"), value).apply() }
    fun readHttpPassword(context: Context) = prefs(context).getString(key(context, "http_password"), "") ?: ""
    fun saveHttpPassword(context: Context, value: String) { prefs(context).edit().putString(key(context, "http_password"), value).apply() }

    fun applyHttpProxy(context: Context, config: JSONObject) {
        val host = readHttpHost(context).trim()
        val port = readHttpPort(context).toIntOrNull() ?: error("Invalid HTTP proxy port")
        require(host.isNotBlank()) { "HTTP proxy host is required" }
        require(port in 1..65535) { "HTTP proxy port must be 1-65535" }

        val user = readHttpUser(context)
        val password = readHttpPassword(context)
        val server = JSONObject().put("address", host).put("port", port)
        if (user.isNotBlank()) {
            server.put("users", JSONArray().put(JSONObject().put("user", user).put("pass", password)))
        }
        val proxySettings = JSONObject().put("servers", JSONArray().put(server))

        val outbounds = config.optJSONArray("outbounds") ?: JSONArray().also { config.put("outbounds", it) }
        val httpOutbound = JSONObject()
            .put("tag", "kupal-http-proxy")
            .put("protocol", "http")
            .put("settings", proxySettings)

        val existingOutbound = (0 until outbounds.length()).firstOrNull {
            outbounds.optJSONObject(it)?.optString("tag") == "kupal-http-proxy"
        }
        if (existingOutbound == null) outbounds.put(httpOutbound)
        else outbounds.put(existingOutbound, httpOutbound)

        val routing = config.optJSONObject("routing") ?: JSONObject().also { config.put("routing", it) }
        val rules = routing.optJSONArray("rules") ?: JSONArray().also { routing.put("rules", it) }
        val routeRule = JSONObject()
            .put("type", "field")
            .put("inboundTag", JSONArray().put("kupal-tun-in"))
            .put("outboundTag", "kupal-http-proxy")
        val existingRule = (0 until rules.length()).firstOrNull {
            rules.optJSONObject(it)?.optString("outboundTag") == "kupal-http-proxy"
        }
        if (existingRule == null) rules.put(routeRule)
        else rules.put(existingRule, routeRule)
    }
}
