package com.kupalvpn.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class ImportedProfile(
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val outbound: JSONObject,
    val warnings: List<String> = emptyList()
) {
    fun toXrayConfig(): String = JSONObject()
        .put("log", JSONObject().put("loglevel", "warning"))
        .put("inbounds", JSONArray())
        .put("outbounds", JSONArray().put(outbound))
        .toString(2)
}

object ProfileLinkParser {
    fun parse(input: String): ImportedProfile {
        val text = input.trim()
        require(text.isNotEmpty()) { "Paste a share link first." }
        return when (text.substringBefore(":").lowercase()) {
            "vless" -> parseVless(text)
            "vmess" -> parseVmess(text)
            "trojan" -> parseTrojan(text)
            "ss" -> parseShadowsocks(text)
            else -> throw IllegalArgumentException("Unsupported link. Use VLESS, VMess, Trojan, or Shadowsocks.")
        }
    }

    private fun parseVless(text: String): ImportedProfile {
        val uri = URI(text)
        val uuid = decode(uri.rawUserInfo ?: "")
        require(uuid.isNotBlank()) { "VLESS link is missing its UUID." }
        val server = requireServer(uri)
        val port = requirePort(uri)
        val q = query(uri)
        val network = q["type"] ?: q["network"] ?: "tcp"
        val security = q["security"] ?: "none"
        val user = JSONObject().put("id", uuid).put("encryption", q["encryption"] ?: "none")
        q["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
        val vnext = JSONObject().put("address", server).put("port", port).put("users", JSONArray().put(user))
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
        applyStreamSettings(outbound, network, security, q)
        return ImportedProfile(label(uri, "VLESS $server:$port"), "VLESS", server, port, outbound)
    }

    private fun parseVmess(text: String): ImportedProfile {
        val raw = text.substringAfter("://", "").substringBefore('#')
        val json = JSONObject(decodeBase64(raw))
        val server = json.optString("add").trim()
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port")
        val uuid = json.optString("id")
        require(server.isNotBlank()) { "VMess profile is missing its server address." }
        require(port in 1..65535) { "VMess port is invalid." }
        require(uuid.isNotBlank()) { "VMess profile is missing its user ID." }
        val network = json.optString("net", "tcp").ifBlank { "tcp" }
        val security = json.optString("tls", "").let { if (it.equals("tls", true)) "tls" else "none" }
        val user = JSONObject().put("id", uuid).put("alterId", json.optInt("aid", 0)).put("security", json.optString("scy", "auto"))
        val vnext = JSONObject().put("address", server).put("port", port).put("users", JSONArray().put(user))
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
        val q = mutableMapOf<String, String>()
        json.optString("host").takeIf { it.isNotBlank() }?.let { q["host"] = it }
        json.optString("path").takeIf { it.isNotBlank() }?.let { q["path"] = it }
        json.optString("sni").takeIf { it.isNotBlank() }?.let { q["sni"] = it }
        json.optString("fp").takeIf { it.isNotBlank() }?.let { q["fp"] = it }
        applyStreamSettings(outbound, network, security, q)
        return ImportedProfile(json.optString("ps").ifBlank { "VMess $server:$port" }, "VMess", server, port, outbound)
    }

    private fun parseTrojan(text: String): ImportedProfile {
        val uri = URI(text)
        val password = decode(uri.rawUserInfo ?: "")
        require(password.isNotBlank()) { "Trojan link is missing its password." }
        val server = requireServer(uri)
        val port = requirePort(uri)
        val q = query(uri)
        val serverEntry = JSONObject().put("address", server).put("port", port).put("password", password)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(serverEntry)))
        applyStreamSettings(outbound, q["type"] ?: q["network"] ?: "tcp", q["security"] ?: "tls", q)
        return ImportedProfile(label(uri, "Trojan $server:$port"), "Trojan", server, port, outbound)
    }

    private fun parseShadowsocks(text: String): ImportedProfile {
        val body = text.substringAfter("://", "").substringBefore('#')
        val decodedBody = if (!body.contains('@')) decodeBase64(body) else body
        val at = decodedBody.lastIndexOf('@')
        require(at > 0) { "Shadowsocks link must contain method:password and server@port." }
        val credentialRaw = decodedBody.substring(0, at)
        val credential = if (credentialRaw.contains(":")) credentialRaw else decodeBase64(credentialRaw)
        val endpoint = decodedBody.substring(at + 1)
        val colon = credential.indexOf(':')
        require(colon > 0) { "Shadowsocks credentials are malformed." }
        val method = decode(credential.substring(0, colon))
        val password = decode(credential.substring(colon + 1))
        val endpointUri = URI("ss://$endpoint")
        val server = requireServer(endpointUri)
        val port = requirePort(endpointUri)
        val ssServer = JSONObject().put("address", server).put("port", port).put("method", method).put("password", password)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(ssServer)))
        val plugin = endpointUri.rawQuery?.let { query(endpointUri)["plugin"] }
        val warnings = if (!plugin.isNullOrBlank()) listOf("Shadowsocks plugin '$plugin' is not imported; this node may require plugin support.") else emptyList()
        return ImportedProfile(labelFromFragment(text), "Shadowsocks", server, port, outbound, warnings)
    }

    private fun applyStreamSettings(outbound: JSONObject, network: String, security: String, q: Map<String, String>) {
        val normalizedNetwork = when (network.lowercase()) {
            "websocket" -> "ws"
            "h2" -> "http"
            "splithttp" -> "xhttp"
            else -> network.lowercase()
        }
        require(normalizedNetwork in setOf("tcp", "ws", "grpc", "http", "xhttp", "kcp", "quic")) {
            "Unsupported transport '$network'."
        }
        require(security.lowercase() in setOf("none", "tls", "reality")) { "Unsupported stream security '$security'." }
        val stream = JSONObject().put("network", normalizedNetwork).put("security", security.lowercase())
        when (normalizedNetwork) {
            "ws" -> {
                val headers = JSONObject()
                q["host"]?.takeIf { it.isNotBlank() }?.let { headers.put("Host", it) }
                val ws = JSONObject().put("path", q["path"] ?: "/")
                if (headers.length() > 0) ws.put("headers", headers)
                stream.put("wsSettings", ws)
            }
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", q["serviceName"] ?: q["path"] ?: "").put("multiMode", q["mode"] == "multi"))
            "http" -> {
                val hosts = q["host"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.let { JSONArray(it) } ?: JSONArray()
                stream.put("httpSettings", JSONObject().put("path", q["path"] ?: "/").put("host", hosts))
            }
            "xhttp" -> {
                val xhttp = JSONObject().put("path", q["path"] ?: "/").put("mode", q["mode"] ?: "auto")
                q["host"]?.takeIf { it.isNotBlank() }?.let { xhttp.put("host", it) }
                stream.put("xhttpSettings", xhttp)
            }
            "tcp" -> q["headerType"]?.let { stream.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", it))) }
            "kcp" -> {
                val kcp = JSONObject().put("mtu", q["mtu"]?.toIntOrNull() ?: 1350)
                    .put("tti", q["tti"]?.toIntOrNull() ?: 50)
                    .put("uplinkCapacity", q["uplink"]?.toIntOrNull() ?: 5)
                    .put("downlinkCapacity", q["downlink"]?.toIntOrNull() ?: 20)
                    .put("congestion", q["congestion"] == "1" || q["congestion"] == "true")
                    .put("header", JSONObject().put("type", q["headerType"] ?: "none"))
                q["seed"]?.takeIf { it.isNotBlank() }?.let { kcp.put("seed", it) }
                stream.put("kcpSettings", kcp)
            }
            "quic" -> stream.put("quicSettings", JSONObject().put("security", q["quicSecurity"] ?: "none").put("key", q["key"] ?: "").put("header", JSONObject().put("type", q["headerType"] ?: "none")))
        }
        when (security.lowercase()) {
            "tls" -> {
                val tls = JSONObject().put("allowInsecure", q["allowInsecure"] == "1" || q["allowInsecure"] == "true")
                q["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("serverName", it) }
                q["fp"]?.takeIf { it.isNotBlank() }?.let { tls.put("fingerprint", it) }
                q["alpn"]?.takeIf { it.isNotBlank() }?.let { tls.put("alpn", JSONArray(it.split(',').map(String::trim))) }
                stream.put("tlsSettings", tls)
            }
            "reality" -> {
                val reality = JSONObject()
                q["sni"]?.let { reality.put("serverName", it) }
                q["fp"]?.let { reality.put("fingerprint", it) }
                q["pbk"]?.let { reality.put("publicKey", it) }
                q["sid"]?.let { reality.put("shortId", it) }
                q["spx"]?.let { reality.put("spiderX", it) }
                stream.put("realitySettings", reality)
            }
        }
        outbound.put("streamSettings", stream)
    }

    private fun requireServer(uri: URI): String = (uri.host ?: "").trim().also {
        require(it.isNotBlank()) { "Share link is missing its server address." }
    }

    private fun requirePort(uri: URI): Int = uri.port.also {
        require(it in 1..65535) { "Share link is missing a valid port." }
    }

    private fun query(uri: URI): Map<String, String> {
        val result = mutableMapOf<String, String>()
        uri.rawQuery?.split('&')?.forEach { item ->
            if (item.isNotBlank()) {
                val key = decode(item.substringBefore('='))
                val value = decode(item.substringAfter('=', ""))
                result[key] = value
            }
        }
        return result
    }

    private fun label(uri: URI, fallback: String) = uri.fragment?.let(::decode)?.takeIf { it.isNotBlank() } ?: fallback
    private fun labelFromFragment(text: String): String = text.substringAfter('#', "Shadowsocks").let(::decode).ifBlank { "Shadowsocks" }
    private fun decode(text: String): String = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")

    private fun decodeBase64(text: String): String {
        val normalized = text.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = try {
            Base64.getDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            Base64.getUrlDecoder().decode(text.trim())
        }
        return String(bytes, StandardCharsets.UTF_8)
    }
}
