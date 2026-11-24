package com.kupalvpn.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProfileLinkParserTest {
    @Test fun vlessRealityWsImportsTransportAndTlsFields() {
        val profile = ProfileLinkParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@example.org:443?type=ws&security=reality&sni=cdn.example.org&fp=chrome&pbk=publicKey&sid=abcd&path=%2Fedge&host=cdn.example.org&flow=xtls-rprx-vision#Test%20VLESS"
        )
        assertEquals("VLESS", profile.protocol)
        assertEquals("Test VLESS", profile.name)
        assertEquals(443, profile.port)
        val stream = profile.outbound.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("reality", stream.getString("security"))
        assertEquals("publicKey", stream.getJSONObject("realitySettings").getString("publicKey"))
        assertEquals("/edge", stream.getJSONObject("wsSettings").getString("path"))
    }

    @Test fun vmessBase64JsonImportsUserAndWs() {
        val json = JSONObject()
            .put("v", "2")
            .put("ps", "VMess Test")
            .put("add", "vmess.example.net")
            .put("port", "8443")
            .put("id", "11111111-2222-3333-4444-555555555555")
            .put("aid", "0")
            .put("scy", "auto")
            .put("net", "ws")
            .put("tls", "tls")
            .put("host", "cdn.example.net")
            .put("path", "/v")
        val share = "vmess://" + Base64.getEncoder().encodeToString(json.toString().toByteArray())
        val profile = ProfileLinkParser.parse(share)
        assertEquals("VMess Test", profile.name)
        assertEquals("vmess", profile.outbound.getString("protocol"))
        assertEquals("tls", profile.outbound.getJSONObject("streamSettings").getString("security"))
        assertEquals("cdn.example.net", profile.outbound.getJSONObject("streamSettings").getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
    }

    @Test fun trojanDefaultsToTlsAndParsesSni() {
        val profile = ProfileLinkParser.parse("trojan://pass%3Aword@trojan.example:443?sni=www.example.com#Trojan")
        assertEquals("Trojan", profile.name)
        assertEquals("pass:word", profile.outbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("password"))
        assertEquals("www.example.com", profile.outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("serverName"))
    }

    @Test fun shadowsocksSIP002AndLegacyLinksParse() {
        val auth = Base64.getEncoder().encodeToString("aes-128-gcm:secret".toByteArray())
        val sip002 = ProfileLinkParser.parse("ss://$auth@ss.example:8388#SS%20Profile")
        assertEquals("SS Profile", sip002.name)
        assertEquals("aes-128-gcm", sip002.outbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("method"))

        val legacy = Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:pw@legacy.example:443".toByteArray())
        val legacyProfile = ProfileLinkParser.parse("ss://$legacy")
        assertEquals("legacy.example", legacyProfile.server)
    }


    @Test fun generatedDraftHasOutboundsAndNoActiveTunYet() {
        val profile = ProfileLinkParser.parse("vless://11111111-2222-3333-4444-555555555555@host.example:443#x")
        val config = JSONObject(profile.toXrayConfig())
        assertEquals(1, config.getJSONArray("outbounds").length())
        assertEquals(0, config.getJSONArray("inbounds").length())
    }


    @Test fun unsupportedSchemeIsRejectedClearly() {
        val message = try {
            ProfileLinkParser.parse("hysteria2://user@host:443")
            ""
        } catch (error: IllegalArgumentException) {
            error.message.orEmpty()
        }
        assertTrue(message.contains("Unsupported link"))
    }
}
