package com.kupalvpn.app

import org.json.JSONObject
import java.util.Base64

/** Parses pasted node lists without fetching subscription URLs from the network. */
object SubscriptionParser {
    const val MAX_PROFILES = 100
    private const val JSON_WARNING = "Xray JSON import extracts outbounds only; routing, DNS, and inbounds are not copied."

    fun parse(input: String): List<ImportedProfile> {
        val text = input.trim()
        require(text.isNotEmpty()) { "Paste a share link, subscription text, or Xray JSON first." }
        if (text.startsWith("{")) return parseXrayJson(text)

        val directLinks = shareLinksFromLines(text)
        if (directLinks.isNotEmpty()) return parseLinks(directLinks, text.lines().count { it.isNotBlank() } - directLinks.size)
        val decoded = decodeBase64(text)
            ?: throw IllegalArgumentException("Input is not a supported share link or Base64 subscription text.")
        val decodedText = decoded.trim()
        if (decodedText.startsWith("{")) return parseXrayJson(decodedText)
        val decodedLinks = shareLinksFromLines(decodedText)
        require(decodedLinks.isNotEmpty()) { "Subscription text contains no supported VLESS, VMess, Trojan, or Shadowsocks links." }
        return parseLinks(decodedLinks, decodedText.lines().count { it.isNotBlank() } - decodedLinks.size)
    }

    private fun parseXrayJson(text: String): List<ImportedProfile> {
        val root = JSONObject(text)
        val outbounds = root.optJSONArray("outbounds") ?: throw IllegalArgumentException("Xray JSON must contain an outbounds array.")
        require(outbounds.length() in 1..MAX_PROFILES) { "Xray JSON must contain between 1 and $MAX_PROFILES outbounds." }
        val profiles = (0 until outbounds.length()).mapNotNull { index ->
            val outbound = outbounds.optJSONObject(index) ?: return@mapNotNull null
            val protocol = outbound.optString("protocol", "unknown")
            val settings = outbound.optJSONObject("settings")
            val endpoint = settings?.optJSONArray("servers")?.optJSONObject(0)
                ?: settings?.optJSONArray("vnext")?.optJSONObject(0)
                ?: settings?.optJSONArray("peers")?.optJSONObject(0)
            val server = endpoint?.optString("address").orEmpty()
            val port = endpoint?.optInt("port", 0) ?: 0
            val tag = outbound.optString("tag").ifBlank { "imported-" + protocol + "-" + (index + 1) }
            ImportedProfile(tag, protocol.uppercase(), server, port, JSONObject(outbound.toString()).put("tag", tag), listOf(JSON_WARNING))
        }
        require(profiles.isNotEmpty()) { "Xray JSON has no usable outbound objects." }
        return profiles
    }

    private fun parseLinks(links: List<String>, ignoredLineCount: Int): List<ImportedProfile> {
        require(links.size <= MAX_PROFILES) { "Subscription contains more than $MAX_PROFILES nodes; split it into smaller imports." }
        val parsed = links.mapNotNull { link -> runCatching { ProfileLinkParser.parse(link) }.getOrNull() }
        require(parsed.isNotEmpty()) { "No valid supported profile links found." }
        val skipped = (ignoredLineCount + links.size - parsed.size).coerceAtLeast(0)
        if (skipped > 0) return parsed.map { it.copy(warnings = it.warnings + "Skipped $skipped malformed or unsupported line(s).") }
        return parsed
    }

    private fun shareLinksFromLines(text: String): List<String> = text.lines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && isSupportedLink(it) }

    private fun isSupportedLink(line: String): Boolean = when (line.substringBefore(":").lowercase()) {
        "vless", "vmess", "trojan", "ss" -> true
        else -> false
    }

    private fun decodeBase64(text: String): String? {
        val clean = text.trim().replace("-", "+").replace("_", "/").replace("\\s".toRegex(), "")
        if (clean.isEmpty()) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded) }
            .recoverCatching { Base64.getUrlDecoder().decode(text.trim()) }
            .getOrNull() ?: return null
        return bytes.toString(Charsets.UTF_8)
    }
}
