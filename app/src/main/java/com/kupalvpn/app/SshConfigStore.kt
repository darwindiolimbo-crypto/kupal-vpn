package com.kupalvpn.app

import android.content.Context

data class SshConfig(
    val mode: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val proxyHost: String,
    val proxyPort: Int,
    val sniHost: String,
    val payload: String
) {
    fun validate() {
        require(host.isNotBlank()) { "SSH server host is required" }
        require(port in 1..65535) { "SSH server port must be 1-65535" }
        require(username.isNotBlank()) { "SSH username is required" }
        require(password.isNotBlank()) { "SSH password is required" }
        if (mode == MODE_HTTP_PAYLOAD_PROXY || mode == MODE_TLS_PAYLOAD_PROXY) {
            require(proxyHost.isNotBlank()) { "HTTP proxy host is required for this mode" }
            require(proxyPort in 1..65535) { "HTTP proxy port must be 1-65535" }
        }
        if (mode != MODE_DIRECT) require(payload.isNotBlank()) { "HTTP payload is required for this mode" }
        if (mode == MODE_TLS_PAYLOAD_PROXY) require(sniHost.isNotBlank()) { "TLS SNI host is required" }
    }

    companion object {
        const val MODE_DIRECT = "direct_tcp"
        const val MODE_HTTP_PAYLOAD_DIRECT = "http_payload_direct"
        const val MODE_HTTP_PAYLOAD_PROXY = "http_payload_proxy"
        const val MODE_TLS_PAYLOAD_PROXY = "tls_payload_proxy"
    }
}

/** Per-profile SSH transport settings. These settings are kept locally on this device. */
object SshConfigStore {
    private const val PREFS = "kupal_ssh_profiles"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(context: Context, field: String) = "${ProfileStore.activeProfileId(context)}_$field"

    fun read(context: Context): SshConfig {
        val p = prefs(context)
        return SshConfig(
            mode = p.getString(key(context, "mode"), SshConfig.MODE_DIRECT) ?: SshConfig.MODE_DIRECT,
            host = p.getString(key(context, "host"), "") ?: "",
            port = p.getString(key(context, "port"), "22")?.toIntOrNull() ?: 22,
            username = p.getString(key(context, "username"), "") ?: "",
            password = p.getString(key(context, "password"), "") ?: "",
            proxyHost = p.getString(key(context, "proxy_host"), "") ?: "",
            proxyPort = p.getString(key(context, "proxy_port"), "8080")?.toIntOrNull() ?: 8080,
            sniHost = p.getString(key(context, "sni_host"), "") ?: "",
            payload = p.getString(key(context, "payload"), DEFAULT_PAYLOAD) ?: DEFAULT_PAYLOAD
        )
    }

    fun save(context: Context, config: SshConfig) {
        prefs(context).edit()
            .putString(key(context, "mode"), config.mode)
            .putString(key(context, "host"), config.host.trim())
            .putString(key(context, "port"), config.port.toString())
            .putString(key(context, "username"), config.username)
            .putString(key(context, "password"), config.password)
            .putString(key(context, "proxy_host"), config.proxyHost.trim())
            .putString(key(context, "proxy_port"), config.proxyPort.toString())
            .putString(key(context, "sni_host"), config.sniHost.trim())
            .putString(key(context, "payload"), config.payload)
            .apply()
    }

    const val DEFAULT_PAYLOAD = "CONNECT [host_port] HTTP/1.1[crlf]Host: [host_port][crlf]Proxy-Connection: Keep-Alive[crlf][crlf]"
}
