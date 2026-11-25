package com.kupalvpn.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Accepts common compatible config JSON, share links and Base64 subscriptions. */
object RemoteConfigStore {
    const val CONFIG_URL = "https://raw.githubusercontent.com/darwindiolimbo-crypto/kupal-vpn/main/config/kupalvpn-config.json"
    const val EDIT_URL = "https://github.com/darwindiolimbo-crypto/kupal-vpn/edit/main/config/kupalvpn-config.json"
    private const val PREFS = "kupal_remote_config"
    private const val KEY_JSON = "cached_config_json"
    private const val MAX_BYTES = 512 * 1024
    private const val MAX_ITEMS = 200
    private const val FALLBACK = """{"schemaVersion":1,"configVersion":"bundled","payloadTemplates":[],"dnsPresets":[],"servers":[],"xrayProfiles":[]}"""

    fun read(context: Context): JSONObject {
        val raw=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_JSON,null) ?: FALLBACK
        return runCatching { normalize(JSONObject(raw)) }.getOrElse { JSONObject(FALLBACK) }
    }

    internal fun normalize(input: JSONObject): JSONObject {
        val root=JSONObject(input.toString())
        fun findArray(keys:List<String>):JSONArray? {
            for(k in keys) {
                root.optJSONArray(k)?.let { return it }
                root.optJSONObject(k)?.let { obj ->
                    obj.optJSONArray("items")?.let { return it }
                    obj.optJSONArray("data")?.let { return it }
                }
            }
            for(w in listOf("data","result","catalog","config","payload")) {
                val obj=root.optJSONObject(w) ?: continue
                for(k in keys) obj.optJSONArray(k)?.let { return it }
            }
            return null
        }
        val payloads=findArray(listOf("payloadTemplates","payload_templates","payloads","http_payloads","httpPayloads","payload_list","payloadList")) ?: JSONArray()
        val dns=findArray(listOf("dnsPresets","dns_presets","dns","dns_list")) ?: JSONArray()
        val servers=findArray(listOf("servers","server_list","serverList","server_catalog","serverCatalog","nodes","entries")) ?: JSONArray()
        val profiles=findArray(listOf("xrayProfiles","xray_profiles","profiles","configs","config_list","configList","subscriptions")) ?: JSONArray()
        val normalizedProfiles=JSONArray()
        for(i in 0 until profiles.length().coerceAtMost(MAX_ITEMS)) {
            val item=profiles.opt(i)
            if(item is JSONObject) normalizedProfiles.put(item)
            else if(item is String) decodeSubscription(item)?.let { normalizedProfiles.put(JSONObject().put("name","Subscription").put("content",it)) }
        }
        var version=""
        for(k in listOf("configVersion","config_version","version","versionName","updated_at")) {
            val v=root.opt(k)
            if(v is String && v.isNotBlank()) { version=v.trim(); break }
        }
        if(version.isBlank()) version="remote"
        return JSONObject().put("schemaVersion",1).put("configVersion",version)
            .put("payloadTemplates",payloads).put("dnsPresets",dns).put("servers",servers)
            .put("xrayProfiles",normalizedProfiles).put("sourceFormat",root.optString("sourceFormat",root.optString("format","auto")))
    }

    private fun decodeSubscription(raw:String):String? {
        val text=raw.trim()
        if(text.startsWith("vless://")||text.startsWith("vmess://")||text.startsWith("trojan://")||text.startsWith("ss://")||text.startsWith("{")||text.startsWith("[")) return text
        val clean=text.replace("\\s".toRegex(),"").replace('-','+').replace('_','/')
        if(clean.length<16) return null
        val padded=clean+"=".repeat((4-clean.length%4)%4)
        val decoded=runCatching { String(Base64.getDecoder().decode(padded),Charsets.UTF_8) }
            .recoverCatching { String(Base64.getUrlDecoder().decode(text),Charsets.UTF_8) }.getOrNull() ?: return null
        val d=decoded.trim()
        return d.takeIf { it.startsWith("{")||it.startsWith("[")||it.contains("://") }
    }

    suspend fun update(context:Context):String = withContext(Dispatchers.IO) {
        var connection:HttpURLConnection?=null
        try {
            connection=(URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
                requestMethod="GET"; connectTimeout=15000; readTimeout=15000; useCaches=false; instanceFollowRedirects=true
                setRequestProperty("Accept","application/json,text/plain,*/*"); setRequestProperty("User-Agent","KupalVPN-Android")
            }
            val code=connection.responseCode
            if(code!=HttpURLConnection.HTTP_OK) error("Config source returned HTTP "+code)
            val body=connection.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192); var total=0
                while(true) { val n=input.read(buffer); if(n<0) break; total+=n
                    require(total<=MAX_BYTES) { "Config response exceeds 512 KB" }; output.write(buffer,0,n) }
                output.toByteArray().toString(Charsets.UTF_8)
            }
            val parsed=runCatching { JSONObject(body) }.getOrElse {
                val decoded=decodeSubscription(body) ?: error("Unsupported config format: JSON, share link or Base64 subscription expected")
                JSONObject().put("profiles",JSONArray().put(decoded))
            }
            val normalized=normalize(parsed)
            val hasContent=normalized.getJSONArray("payloadTemplates").length()>0||normalized.getJSONArray("servers").length()>0||
                normalized.getJSONArray("xrayProfiles").length()>0||normalized.getJSONArray("dnsPresets").length()>0
            require(hasContent) { "Config JSON contains no supported payload, server, DNS or profile entries" }
            check(context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString(KEY_JSON,normalized.toString()).commit()) { "Unable to save config cache" }
            "Updated config catalog ("+normalized.optString("configVersion","latest")+")"
        } catch(error:Exception) {
            throw IllegalStateException("Config update failed: "+(error.message ?: error.javaClass.simpleName),error)
        } finally { connection?.disconnect() }
    }
}
