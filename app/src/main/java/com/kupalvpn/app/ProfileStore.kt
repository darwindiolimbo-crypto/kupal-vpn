package com.kupalvpn.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class VpnProfile(val id: String, val name: String, val carrier: String, val apn: String, val protocol: String, val server: String)

object ProfileStore {
    private const val PREFS = "kupal_vpn_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activeProfileId(context: Context): String {
        ensureDefaults(context)
        return prefs(context).getString(KEY_ACTIVE, "my-profile") ?: "my-profile"
    }

    fun profiles(context: Context): List<VpnProfile> {
        ensureDefaults(context)
        val array = JSONArray(prefs(context).getString(KEY_PROFILES, "[]") ?: "[]")
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            VpnProfile(o.optString("id"), o.optString("name"), o.optString("carrier"), o.optString("apn"), o.optString("protocol", "VLESS"), o.optString("server"))
        }
    }

    fun select(context: Context, id: String) {
        if (profiles(context).any { it.id == id }) prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun add(context: Context, carrier: String, apn: String) {
        val p = VpnProfile(UUID.randomUUID().toString(), "$carrier profile", carrier, apn, "VLESS", "")
        save(context, profiles(context) + p)
        select(context, p.id)
    }

    fun update(context: Context, profile: VpnProfile) {
        save(context, profiles(context).map { if (it.id == profile.id) profile else it })
    }

    fun delete(context: Context, id: String) {
        val left = profiles(context).filterNot { it.id == id }
        if (left.isEmpty()) return
        save(context, left)
        if (activeProfileId(context) == id) select(context, left.first().id)
    }

    private fun save(context: Context, list: List<VpnProfile>) {
        val array = JSONArray()
        list.forEach { p -> array.put(JSONObject().apply {
            put("id", p.id); put("name", p.name); put("carrier", p.carrier)
            put("apn", p.apn); put("protocol", p.protocol); put("server", p.server)
        }) }
        prefs(context).edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    private fun ensureDefaults(context: Context) {
        val p = prefs(context)
        if (p.contains(KEY_PROFILES)) return
        save(context, listOf(
            VpnProfile("my-profile", "My VPN", "Custom", "", "VLESS", ""),
            VpnProfile("dito-default", "DITO", "DITO", "internet.dito.ph", "VLESS", ""),
            VpnProfile("smart-default", "Smart", "Smart", "internet", "VLESS", ""),
            VpnProfile("tnt-default", "TNT / Talk 'N Text", "TNT", "internet", "VLESS", ""),
            VpnProfile("globe-default", "Globe", "Globe", "internet.globe.com.ph", "VLESS", ""),
            VpnProfile("tm-default", "TM", "TM", "internet.globe.com.ph", "VLESS", "")
        ))
        p.edit().putString(KEY_ACTIVE, "my-profile").apply()
    }
}

@Composable
fun ProfilesScreen(padding: androidx.compose.foundation.layout.PaddingValues) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(ProfileStore.profiles(context)) }
    var activeId by rememberSaveable { mutableStateOf(ProfileStore.activeProfileId(context)) }
    var editingId by rememberSaveable { mutableStateOf(activeId) }
    val selected = profiles.firstOrNull { it.id == editingId } ?: profiles.first()
    var name by remember(selected.id) { mutableStateOf(selected.name) }
    var apn by remember(selected.id) { mutableStateOf(selected.apn) }
    var protocol by remember(selected.id) { mutableStateOf(selected.protocol) }
    var server by remember(selected.id) { mutableStateOf(selected.server) }
    fun reload() { profiles = ProfileStore.profiles(context); activeId = ProfileStore.activeProfileId(context) }

    ScreenColumn(padding) {
        Text("VPN profiles", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Save multiple carrier/server profiles and switch the active profile. Each profile keeps its own Xray JSON and payload draft.", color = Color.LightGray, fontSize = 13.sp)
        SectionCard("Add carrier profile") {
            listOf("DITO" to "internet.dito.ph", "Smart" to "internet", "TNT / Talk 'N Text" to "internet", "Globe" to "internet.globe.com.ph", "TM" to "internet.globe.com.ph", "Custom" to "").forEach { (carrier, value) ->
                OutlinedButton(onClick = { ProfileStore.add(context, carrier, value); reload(); editingId = activeId }, modifier = Modifier.fillMaxWidth()) { Text("＋ $carrier") }
            }
        }
        SectionCard("Saved profiles") {
            profiles.forEach { profile ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { editingId = profile.id },
                    colors = CardDefaults.cardColors(containerColor = if (profile.id == activeId) Color(0xFF174B40) else Color(0xFF193149)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(profile.name, fontWeight = FontWeight.Bold)
                        Text("${profile.carrier} • ${profile.protocol} • APN: ${profile.apn.ifBlank { "not set" }}", color = Color.LightGray, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { ProfileStore.select(context, profile.id); editingId = profile.id; reload() }) { Text(if (profile.id == activeId) "Active" else "Select") }
                            OutlinedButton(onClick = { editingId = profile.id }) { Text("Edit") }
                            if (profiles.size > 1) OutlinedButton(onClick = { ProfileStore.delete(context, profile.id); reload(); editingId = activeId }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        SectionCard("Edit profile") {
            Text("Editing: ${selected.name}", color = Color(0xFF59B9E8), fontWeight = FontWeight.SemiBold)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Profile name") }, singleLine = true)
            OutlinedTextField(apn, { apn = it }, Modifier.fillMaxWidth(), label = { Text("Carrier APN (reference only)") }, singleLine = true)
            OutlinedTextField(protocol, { protocol = it }, Modifier.fillMaxWidth(), label = { Text("Protocol: VLESS / VMess / Trojan / Shadowsocks") }, singleLine = true)
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Authorized proxy/server address") }, singleLine = true)
            Button(onClick = {
                ProfileStore.update(context, selected.copy(name = name.trim().ifBlank { selected.name }, apn = apn.trim(), protocol = protocol.trim().ifBlank { "VLESS" }, server = server.trim()))
                reload()
            }, modifier = Modifier.fillMaxWidth()) { Text("Save profile") }
            Text("APN is informational only; this app does not change Android carrier settings. Use your carrier's normal data plan and an authorized proxy/server.", color = Color(0xFFFFD27A), fontSize = 12.sp)
        }
    }
}
