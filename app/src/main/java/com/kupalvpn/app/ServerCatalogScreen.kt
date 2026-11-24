package com.kupalvpn.app

import android.content.ClipData
import android.content.ClipboardManager
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
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ServerCatalogScreen(padding: androidx.compose.foundation.layout.PaddingValues) {
    val context=LocalContext.current; val scope=rememberCoroutineScope()
    var catalog by remember { mutableStateOf(ServerCatalogStore.read(context)) }
    var loading by remember { mutableStateOf(false) }; var search by rememberSaveable { mutableStateOf("") }; var status by rememberSaveable { mutableStateOf("") }
    suspend fun refresh() {
        loading=true; status="Updating public server catalogue…"
        try { catalog=ServerCatalogStore.refresh(context); status=if(catalog.items.isEmpty()) "No importable server entries were returned." else "Loaded \${catalog.items.size} servers." }
        catch(e:Exception) { status=e.message ?: "Server update failed"; catalog=ServerCatalogStore.read(context) }
        finally { loading=false }
    }
    LaunchedEffect(Unit) { if(catalog.items.isEmpty()) refresh() }
    val shown=remember(catalog.items,search) { if(search.isBlank()) catalog.items else catalog.items.filter { it.name.contains(search,true)||it.protocol.contains(search,true)||it.country.contains(search,true)||it.host.contains(search,true) } }
    ScreenColumn(padding) {
        Text("Free servers",fontSize=22.sp,fontWeight=FontWeight.Bold)
        Text("Live list from MobiVPN’s public server update endpoint. Public servers can change or be unavailable in your area.",color=Color.LightGray,fontSize=13.sp)
        SectionCard("Server list update") {
            Text("JuanScript / MobiVPN catalogue",fontWeight=FontWeight.SemiBold)
            Text(ServerCatalogStore.CATALOG_URL,color=Color(0xFF59B9E8),fontSize=10.sp)
            Text("Version: \${catalog.version} · Updated: \${catalog.updatedAt}",color=Color.LightGray,fontSize=11.sp)
            if(catalog.fetchedAt>0L) Text("Fetched: \${DateFormat.getDateTimeInstance().format(Date(catalog.fetchedAt))}",color=Color.LightGray,fontSize=11.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()) {
                Button(onClick={scope.launch { refresh() }},enabled=!loading,modifier=Modifier.weight(1f)) { Text(if(loading) "Updating…" else "Update server list") }
                OutlinedButton(onClick={
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("MobiVPN catalogue URL",ServerCatalogStore.CATALOG_URL))
                    status="Update URL copied."
                },modifier=Modifier.weight(1f)) { Text("Copy update URL") }
            }
            if(catalog.notice.isNotBlank()) Text(catalog.notice,color=Color.LightGray,fontSize=11.sp)
            if(status.isNotBlank()) Text(status,color=if(status.contains("failed",true)) Color(0xFFFFD27A) else Color(0xFF66F0B5),fontSize=12.sp)
        }
        OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth(),label={Text("Search server, country or protocol")},singleLine=true)
        Text("\${shown.size} server(s)",color=Color.LightGray,fontSize=12.sp)
        if(shown.isEmpty()) SectionCard("No servers available") {
            Text(if(catalog.items.isEmpty()) "No list is cached. Tap Update server list. If the public endpoint is offline or blocks access, the app will preserve the last successful list." else "No matching servers.",color=Color.LightGray,fontSize=12.sp)
        } else shown.forEach { item ->
            Card(modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(item.name,fontWeight=FontWeight.Bold,fontSize=16.sp)
                    Text(listOf(item.protocol,item.country,if(item.host.isBlank()) "" else if(item.port>0) "\${item.host}:\${item.port}" else item.host).filter{it.isNotBlank()}.joinToString(" • "),color=Color.LightGray,fontSize=11.sp)
                    if(item.description.isNotBlank()) Text(item.description,color=Color.LightGray,fontSize=12.sp)
                    val canAdd=item.shareText.isNotBlank()||item.configJson.isNotBlank()||(item.protocol.contains("ssh",true)&&item.host.isNotBlank()&&item.port in 1..65535)
                    OutlinedButton(onClick={status=runCatching{ServerCatalogStore.useServer(context,item)}.getOrElse{"Could not add server: \${it.message ?: "unsupported entry"}"}},enabled=canAdd,modifier=Modifier.fillMaxWidth()) {
                        Text(if(item.protocol.contains("ssh",true)&&item.shareText.isBlank()&&item.configJson.isBlank()) "Use SSH endpoint" else "Add to profiles")
                    }
                    if(!canAdd) Text("No complete profile or server credentials were supplied for this entry.",color=Color(0xFFFFD27A),fontSize=11.sp)
                }
            }
        }
        SectionCard("Public server warning") {
            Text("These servers are operated by third parties, not Kupal VPN. Do not send banking or sensitive traffic through unknown endpoints.",color=Color(0xFFFFD27A),fontSize=12.sp)
        }
    }
}
