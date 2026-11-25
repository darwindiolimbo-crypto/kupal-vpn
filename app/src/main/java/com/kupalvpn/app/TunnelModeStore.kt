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


/**
 * Checks that an Xray profile contains a real remote outbound.
 * A syntactically valid template with server.example.com / YOUR-UUID-HERE
 * must never be allowed to create a VPN interface and appear connected.
 */
object XrayConfigValidator {
    private val nonProxyProtocols = setOf("freedom", "blackhole", "dns", "loopback", "direct", "block")

    fun validate(configText: String) {
        require(configText.isNotBlank()) { "Xray configuration is empty. Add a server before connecting." }
        val root = try {
            org.json.JSONObject(configText)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid Xray JSON: ${error.message ?: "parse error"}")
        }
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("No Xray outbound is configured. Add a server before connecting.")
        require(outbounds.length() > 0) {
            "No Xray outbound is configured. Add a server before connecting."
        }

        var foundPlaceholder: String? = null
        var foundEndpoint = false
        var foundUsableServer = false
        var missingCredentialsFor: String? = null

        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val protocol = outbound.optString("protocol").trim().lowercase()
            if (protocol.isBlank() || protocol in nonProxyProtocols) continue

            val settings = outbound.optJSONObject("settings") ?: continue
            val candidateArrays = listOfNotNull(
                settings.optJSONArray("vnext"),
                settings.optJSONArray("servers")
            )
            for (array in candidateArrays) {
                for (serverIndex in 0 until array.length()) {
                    val server = array.optJSONObject(serverIndex) ?: continue
                    val host = listOf("address", "server", "host")
                        .firstNotNullOfOrNull { key ->
                            server.optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
                        } ?: continue
                    val port = server.optInt("port", -1)
                    if (port !in 1..65535) continue
                    foundEndpoint = true

                    val normalizedHost = host.lowercase()
                    val placeholder = normalizedHost == "localhost" ||
                        normalizedHost == "0.0.0.0" ||
                        normalizedHost == "127.0.0.1" ||
                        normalizedHost == "::1" ||
                        normalizedHost == "example.com" ||
                        normalizedHost.endsWith(".example.com") ||
                        normalizedHost.contains("your-") ||
                        normalizedHost.contains("replace-me") ||
                        normalizedHost.contains("your_server")
                    if (placeholder) {
                        foundPlaceholder = host
                        continue
                    }

                    val credentialsValid = when {
                        protocol == "vless" || protocol == "vmess" -> {
                            val users = server.optJSONArray("users")
                            val id = users?.optJSONObject(0)?.optString("id")?.trim().orEmpty()
                            id.isNotBlank() &&
                                !id.equals("YOUR-UUID-HERE", ignoreCase = true) &&
                                !id.contains("your-uuid", ignoreCase = true) &&
                                !id.equals("00000000-0000-0000-0000-000000000000", ignoreCase = true)
                        }
                        protocol == "trojan" || protocol.contains("shadowsocks") ||
                            protocol == "hysteria2" -> {
                            val password = server.optString("password").trim()
                            password.isNotBlank() &&
                                !password.equals("YOUR-PASSWORD-HERE", ignoreCase = true) &&
                                !password.contains("your-password", ignoreCase = true)
                        }
                        else -> true
                    }

                    if (credentialsValid) foundUsableServer = true
                    else missingCredentialsFor = protocol
                }
            }
        }

        if (!foundUsableServer) {
            when {
                foundPlaceholder != null ->
                    throw IllegalArgumentException("This profile still uses the placeholder server '$foundPlaceholder'. Select or enter a real server first.")
                missingCredentialsFor != null ->
                    throw IllegalArgumentException("The $missingCredentialsFor server is missing its UUID or password.")
                foundEndpoint ->
                    throw IllegalArgumentException("The configured server entry is not usable. Check its address, port, and credentials.")
                else ->
                    throw IllegalArgumentException("No real server endpoint was found in the Xray outbounds. Import or enter a VLESS, VMess, Trojan, or Shadowsocks server first.")
            }
        }
    }
}
