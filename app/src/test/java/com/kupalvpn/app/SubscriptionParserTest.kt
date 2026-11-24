package com.kupalvpn.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionParserTest {
    private val uuid = "11111111-2222-3333-4444-555555555555"
    private val vless = "vless://$uuid@one.example:443?security=tls#One"
    private val trojan = "trojan://secret@two.example:443?sni=two.example#Two"

    @Test fun importsMultiLinkAndBase64SubscriptionText() {
        val multi = vless + "\n" + trojan
        val direct = SubscriptionParser.parse(multi)
        assertEquals(2, direct.size)
        assertEquals("VLESS", direct.first().protocol)
        assertEquals("Trojan", direct.last().protocol)
        val encoded = Base64.getEncoder().encodeToString(multi.toByteArray())
        assertEquals(2, SubscriptionParser.parse(encoded).size)
    }

    @Test fun importsXrayJsonOutboundsWithWarning() {
        val json = JSONObject().put("outbounds", JSONArray()
            .put(JSONObject().put("protocol", "vless").put("tag", "node-one")
                .put("settings", JSONObject().put("vnext", JSONArray()
                    .put(JSONObject().put("address", "json.example").put("port", 443)))))
            .put(JSONObject().put("protocol", "freedom").put("tag", "direct")))
        val profiles = SubscriptionParser.parse(json.toString())
        assertEquals(2, profiles.size)
        assertEquals("json.example", profiles.first().server)
        assertTrue(profiles.first().warnings.first().contains("outbounds only"))
    }

    @Test fun skipsUnsupportedLinesAndReportsWarning() {
        val profiles = SubscriptionParser.parse(vless + "\ninvalid://not-supported")
        assertEquals(1, profiles.size)
        assertTrue(profiles.first().warnings.any { it.contains("Skipped") })
    }

    @Test fun rejectsEmptyOrUnrecognizedInput() {
        val emptyMessage = runCatching { SubscriptionParser.parse("") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(emptyMessage.contains("Paste"))
        val invalidMessage = runCatching { SubscriptionParser.parse("not a subscription") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(invalidMessage.contains("supported"))
    }
}
