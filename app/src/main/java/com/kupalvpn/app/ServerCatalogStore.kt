package com.kupalvpn.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CatalogServer(
    val id:String,
    val name:String,
    val protocol:String,
    val host:String,
    val port:Int,
    val country:String,
    val shareText:String,
    val configJson:String,
    val description:String,
    val payloadTemplate:String,
    val carrier:String = "",
    val payloadName:String = "",
    val xrayFormat:String = ""
)
data class ServerCatalogSnapshot(val items:List<CatalogServer>,val version:String,val updatedAt:String,val fetchedAt:Long,val notice:String)

object ServerCatalogStore {
    const val CATALOG_URL = "https://www.juanscript.codes/api/catalog?users=1&xray_payload_proxy=1"
    private const val PREFS="kupal_mobivpn_catalog"
    private const val BODY="catalog_body"
    private const val FETCHED="catalog_fetched"
    private const val LIMIT=2*1024*1024

    fun read(context:Context):ServerCatalogSnapshot {
        val p=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val body=p.getString(BODY,null)
        val fetched=p.getLong(FETCHED,0L)
        if(body.isNullOrBlank()) return ServerCatalogSnapshot(emptyList(),"—","—",fetched,"No cached server list")
        return runCatching { parse(body,fetched) }.getOrElse { ServerCatalogSnapshot(emptyList(),"—","—",fetched,"Cached server data is invalid") }
    }

    suspend fun refresh(context:Context):ServerCatalogSnapshot = withContext(Dispatchers.IO) {
        var c:HttpURLConnection?=null
        try {
            c=(URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
                requestMethod="GET"; connectTimeout=15000; readTimeout=20000; useCaches=false; instanceFollowRedirects=true
                setRequestProperty("Accept","application/json")
                setRequestProperty("Cache-Control","no-cache")
                setRequestProperty("User-Agent","KupalVPN-Android/0.2.0")
            }
            val code=c.responseCode
            if(code !in 200..299) error("Server catalogue returned HTTP $code")
            val body=c.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                var total=0
                while(true) {
                    val n=input.read(buffer); if(n<0) break
                    total+=n; require(total<=LIMIT) { "Catalogue response exceeded 2 MB" }
                    output.write(buffer,0,n)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
            val result=parse(body,System.currentTimeMillis())
            context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(BODY,body).putLong(FETCHED,result.fetchedAt).apply()
            result
        } catch(e:Exception) {
            throw IllegalStateException("MobiVPN server update failed: ${e.message ?: e.javaClass.simpleName}",e)
        } finally { c?.disconnect() }
    }

    internal fun parse(body:String,fetchedAt:Long=0L):ServerCatalogSnapshot {
        val root=if(body.trimStart().startsWith("[")) JSONObject().put("data",JSONArray(body)) else JSONObject(body)
        val rows=mutableListOf<CatalogServer>(); val ids=mutableSetOf<String>()
        // These collection names are taken from MobiVPN 0.0.129's catalog model.
        val groups=listOf(
            "servers_json","serversJson","server_json","servers","server_list","serverList",
            "prebuilt_configs","prebuiltConfigs","prebuilt_xrays","prebuiltXrays",
            "third_party_catalog","third_party_config","third_party_configs",
            "configs","configs_v2","nodes","items","entries","catalog","catalogue",
            "payloads","configurations","server_configs","data","result"
        )
        fun pick(o:JSONObject,keys:List<String>):String {
            for(k in keys) { val v=o.opt(k); if(v is String && v.isNotBlank()) return v.trim() }
            return ""
        }
        fun add(o:JSONObject) {
            val endpoint=o.optJSONObject("server") ?: o.optJSONObject("serverData") ?: o
            val host=pick(endpoint,listOf("server_host","serverHost","server_ip","serverIp","ssh_host","sshHost","host","ip","address","hostname","domain","server","target_host"))
            val port=pick(endpoint,listOf("server_port","serverPort","ssh_port","sshPort","port","target_port")).toIntOrNull()
                ?: endpoint.optInt("port",endpoint.optInt("server_port",endpoint.optInt("ssh_port",endpoint.optInt("target_port",0))))
            val protocol=pick(o,listOf("protocol","type","engine","server_type","category","mode","transport","xray_format"))
                .ifBlank { pick(endpoint,listOf("protocol","type")) }
                .ifBlank { if(pick(endpoint,listOf("ssh_host","sshHost")).isNotBlank()) "SSH" else if(o.has("xray")||o.has("xray_json")||o.has("outbounds")) "Xray" else "Server" }
            val link=pick(o,listOf("xray_link","share_link","shareLink","link","uri","profile_link","connection_uri","subscription","config_url"))
            val configObj=o.optJSONObject("config") ?: o.optJSONObject("xray") ?: o.optJSONObject("xray_config") ?: o.optJSONObject("xray_json")
            val configText=when {
                configObj!=null -> configObj.toString()
                o.opt("xray_json") is String -> o.optString("xray_json")
                o.opt("config") is String -> o.optString("config")
                else -> pick(o,listOf("config_json","configJson","xray_config_json","raw_config","content","json","configuration"))
            }
            val config=configText.takeIf { it.trimStart().startsWith("{") || it.trimStart().startsWith("[") }.orEmpty()
            val payloadObj=o.optJSONObject("payload") ?: endpoint.optJSONObject("payload")
            val payloadTemplate=pick(o,listOf("payload","http_payload","payload_text","request_payload","payload_data"))
                .ifBlank { pick(payloadObj ?: JSONObject(),listOf("payload","text","value","request","data")) }
            if(host.isBlank() && link.isBlank() && config.isBlank() && payloadTemplate.isBlank()) return
            val name=pick(o,listOf("payload_name","server_name","serverName","name","title","remark","label","display_name"))
                .ifBlank { host.ifBlank { "Server ${rows.size+1}" } }
            val country=pick(o,listOf("server_country","serverCountry","country","location","server_location","serverLocation","region","city"))
            val carrier=pick(o,listOf("mobile_carrier","carrier","network","operator","sim","apn"))
            val desc=pick(o,listOf("description","details","note","status","server_location_source"))
            val xrayFormat=pick(o,listOf("xray_format","format","config_format"))
            val id=pick(o,listOf("id","server_id","serverId","config_id","uuid")).ifBlank { "${protocol.lowercase()}|${host.lowercase()}|${port}|${name.lowercase()}" }
            if(ids.add(id)) rows += CatalogServer(
                id,name,protocol,host.removePrefix("[").removeSuffix("]"),port,country,link,config,desc,payloadTemplate,carrier,
                pick(o,listOf("payload_name","name","title")),xrayFormat
            )
        }
        fun walk(value:Any?,depth:Int) {
            if(depth>6||value==null) return
            when(value) {
                is JSONArray -> for(i in 0 until value.length()) walk(value.opt(i),depth+1)
                is JSONObject -> {
                    val record=listOf(
                        "host","server_host","serverHost","server_ip","ssh_host","sshHost","ip","address","target_host",
                        "share_link","xray_link","config_json","xray_config","xray_json","protocol","payload","payload_name"
                    ).any { value.has(it) } || value.opt("config") is JSONObject || value.opt("xray") is JSONObject ||
                        value.opt("payload") is JSONObject || value.opt("xray_json") is JSONObject
                    if(record) add(value)
                    for(k in groups) {
                        val child=value.opt(k)
                        if(child is JSONObject||child is JSONArray||child is String) walk(child,depth+1)
                    }
                }
                is String -> {
                    val trimmed=value.trim()
                    if(trimmed.startsWith("{")) runCatching { walk(JSONObject(trimmed),depth+1) }
                    else if(trimmed.startsWith("[")) runCatching { walk(JSONArray(trimmed),depth+1) }
                    else {
                        val links=trimmed.lines().map(String::trim).filter { line ->
                            listOf("vless://","vmess://","trojan://","ss://").any { line.startsWith(it,true) }
                        }
                        if(links.isNotEmpty()) links.forEach { link ->
                            val protocol=link.substringBefore("://").uppercase()
                            val key="link|"+link.hashCode()
                            if(ids.add(key)) rows += CatalogServer(key,protocol+" server",protocol,"",0,"",link,"","","")
                        } else {
                            val clean=trimmed.replace("\\s".toRegex(),"").replace('-','+').replace('_','/')
                            if(clean.length>=16) {
                                val decoded=runCatching { String(java.util.Base64.getDecoder().decode(clean+"=".repeat((4-clean.length%4)%4)),Charsets.UTF_8) }
                                    .recoverCatching { String(java.util.Base64.getUrlDecoder().decode(trimmed),Charsets.UTF_8) }.getOrNull()?.trim()
                                if(!decoded.isNullOrBlank() && (decoded.startsWith("{")||decoded.startsWith("[")||decoded.contains("://"))) walk(decoded,depth+1)
                            }
                        }
                    }
                }
            }
        }
        walk(root,0)
        fun meta(keys:List<String>):String {
            for(k in keys) { val v=root.opt(k); if(v is String&&v.isNotBlank()) return v }
            val data=root.optJSONObject("data")
            if(data!=null) for(k in keys) { val v=data.opt(k); if(v is String&&v.isNotBlank()) return v }
            return ""
        }
        return ServerCatalogSnapshot(rows,meta(listOf("config_version","catalog_version","version","versionName")).ifBlank{"unknown"},meta(listOf("updated_at","updatedAt","last_updated_at")).ifBlank{"not supplied"},fetchedAt,meta(listOf("notice","message","status")))
    }

    fun useServer(context:Context,item:CatalogServer):String {
        if(item.shareText.isNotBlank()) {
            val nodes=SubscriptionParser.parse(item.shareText)
            require(nodes.isNotEmpty()) { "No supported node found in this catalogue entry" }
            var last=""
            nodes.forEach { node ->
                val id=ProfileStore.add(context,"Public catalogue","")
                val current=ProfileStore.profiles(context).first { it.id==id }
                ProfileStore.update(context,current.copy(name=node.name.take(64),protocol=node.protocol,server=node.server))
                LocalStore.writeJson(context,node.toXrayConfig())
                last=id
            }
            if(last.isNotBlank()) ProfileStore.select(context,last)
            TunnelModeStore.saveMode(context,TunnelModeStore.MODE_XRAY)
            return "Imported ${nodes.size} server profile(s)."
        }
        if(item.configJson.isNotBlank()) {
            val obj=JSONObject(item.configJson)
            val json=if(obj.has("outbounds")) obj.toString(2) else JSONObject().put("log",JSONObject().put("loglevel","warning")).put("inbounds",JSONArray()).put("outbounds",JSONArray().put(obj)).toString(2)
            require(JSONObject(json).optJSONArray("outbounds")?.length()?.let { it>0 }==true) { "No Xray outbound in this entry" }
            val id=ProfileStore.add(context,"Public catalogue","")
            val current=ProfileStore.profiles(context).first { it.id==id }
            ProfileStore.update(context,current.copy(name=item.name.take(64),protocol=item.protocol,server=item.host))
            LocalStore.writeJson(context,json); TunnelModeStore.saveMode(context,TunnelModeStore.MODE_XRAY)
            return "Added ${item.name} as an Xray profile."
        }
        if(item.host.isNotBlank() && item.port in 1..65535 && item.protocol.contains("ssh",true)) {
            val id=ProfileStore.add(context,"Public catalogue","")
            val current=ProfileStore.profiles(context).first { it.id==id }
            ProfileStore.update(context,current.copy(name=item.name.take(64),protocol="SSH",server=item.host))
            val previous=SshConfigStore.read(context)
            val sshMode = if (item.payloadTemplate.isNotBlank()) {
                SshConfig.MODE_HTTP_PAYLOAD_DIRECT
            } else {
                previous.mode
            }
            SshConfigStore.save(context, previous.copy(
                mode = sshMode,
                host = item.host,
                port = item.port,
                payload = item.payloadTemplate.ifBlank { previous.payload }
            ))
            TunnelModeStore.saveMode(context,TunnelModeStore.MODE_SSH)
            return "SSH endpoint added. Enter your own username and password in Config before connecting."
        }
        error("This record has no importable configuration. Xray entries need their share link or node credentials.")
    }
}
