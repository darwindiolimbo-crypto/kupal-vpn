package com.kupalvpn.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.LoadAdError
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var adsController: AdsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { LocalStore.initializeFirstRun(applicationContext) }
        adsController = AdsController(this)

        // Render the VPN controls first. Start the optional consent/ads SDK only
        // after the first UI frame so SDK startup cannot block app launch.
        setContent {
            KupalVpnTheme {
                KupalVpnApp(
                    adsController = adsController,
                    onPrivacyOptions = { adsController.showPrivacyOptions() }
                )
            }
        }
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                runCatching { adsController.requestConsentAndInitialize() }
                    .onFailure { android.util.Log.e("KupalVPN", "Deferred ad initialization failed", it) }
            }
        }
    }
}

private val Navy = Color(0xFF050506)
private val Panel = Color(0xB0121216)
private val GlassBorder = Color(0x26FFFFFF)
private val Green = Color(0xFF66F0B5)
private val Accent = Color(0xFF8B7CFF)

@Composable
private fun KupalVpnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green,
            secondary = Color(0xFF59B9E8),
            background = Navy,
            surface = Panel,
            onPrimary = Color(0xFF04130E),
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
private fun KupalVpnApp(
    adsController: AdsController,
    onPrivacyOptions: () -> Unit
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf("Home") }
    var vpnState by remember { mutableStateOf(readEffectiveVpnState(context)) }
    var pendingVpnConfig by remember { mutableStateOf<String?>(null) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val config = pendingVpnConfig
        pendingVpnConfig = null
        if (result.resultCode == Activity.RESULT_OK && config != null) {
            runCatching {
                val serviceIntent = Intent(context, KupalVpnService::class.java)
                    .setAction(KupalVpnService.ACTION_START)
                    .putExtra(KupalVpnService.EXTRA_CONFIG, config)
                if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent)
                else context.startService(serviceIntent)
            }.onFailure { error ->
                vpnState = "error: ${error.message ?: "VPN service failed to start"}"
            }
        }
    }
    val previewConnected = vpnState == "connected"
    val vpnConnecting = vpnState == "connecting"
    fun requestVpnToggle() {
        runCatching {
            if (vpnConnecting) return@runCatching
            if (previewConnected) {
                context.startService(Intent(context, KupalVpnService::class.java).setAction(KupalVpnService.ACTION_STOP))
            } else {
                val validationError = validateVpnConfiguration(context)
                if (validationError != null) {
                    vpnState = "error: $validationError"
                    saveVpnUiState(context, vpnState)
                    return@runCatching
                }
                val config = LocalStore.readJson(context)
                pendingVpnConfig = config
                val permissionIntent = VpnService.prepare(context)
                if (permissionIntent == null) {
                    val serviceIntent = Intent(context, KupalVpnService::class.java)
                        .setAction(KupalVpnService.ACTION_START)
                        .putExtra(KupalVpnService.EXTRA_CONFIG, config)
                    if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent)
                    else context.startService(serviceIntent)
                    pendingVpnConfig = null
                } else {
                    vpnPermissionLauncher.launch(permissionIntent)
                }
            }
        }.onFailure { error ->
            pendingVpnConfig = null
            vpnState = "error: ${error.message ?: "VPN operation failed"}"
            saveVpnUiState(context, vpnState)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            vpnState = readEffectiveVpnState(context)
            delay(500)
        }
    }
    var adsEnabled by rememberSaveable { mutableStateOf(LocalStore.adsEnabled(context)) }
    var remaining by remember { mutableLongStateOf(adsController.remainingMillis()) }
    var vpnTimeBalance by remember { mutableLongStateOf(LocalStore.remainingVpnMillis(context)) }
    var adActionMessage by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(adsEnabled) {
        while (true) {
            remaining = adsController.remainingMillis()
            vpnTimeBalance = LocalStore.remainingVpnMillis(context)
            delay(1_000)
        }
    }

    Scaffold(
        containerColor = Navy,
        bottomBar = {
            NavigationBar(containerColor = Color(0xD90B0B0E)) {
                listOf("Home", "Config", "Logs", "Settings").forEach { destination ->
                    val icon = when (destination) {
                        "Home" -> Icons.Default.Home
                        "Config" -> Icons.Default.Code
                        "Logs" -> Icons.Default.List
                        else -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = tab == destination,
                        onClick = { tab = destination },
                        icon = { androidx.compose.material3.Icon(icon, contentDescription = destination) },
                        label = { Text(destination) }
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            "Home" -> HomeScreen(
                padding = padding,
                previewConnected = previewConnected,
                onPreviewConnectionChange = { requestVpnToggle() },
                vpnStatus = vpnState,
                isConnecting = vpnConnecting,
                vpnTimeMillis = vpnTimeBalance,
                adsController = adsController,
                adsEnabled = adsEnabled,
                adCooldownMillis = remaining,
                adActionMessage = adActionMessage,
                onAdsEnabledChange = {
                    adsEnabled = it
                    LocalStore.setAdsEnabled(context, it)
                },
                onWatchAd = {
                    adActionMessage = adsController.tryShowRewardedAd {
                        LocalStore.grantVpnTime(context)
                        vpnTimeBalance = LocalStore.remainingVpnMillis(context)
                        adActionMessage = "Reward earned: +2 hours VPN time"
                    }
                }
            )
            "Config" -> ConfigScreen(padding = padding)
            "Logs" -> ConnectionLogsScreen(padding = padding)
            else -> SettingsScreen(
                padding = padding,
                onPrivacyOptions = onPrivacyOptions,
                privacyOptionsRequired = adsController.privacyOptionsRequired,
             )
        }
    }
}

@Composable
fun ScreenColumn(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = { content() }
    )
}

@Composable
private fun HomeScreen(
    padding: PaddingValues,
    previewConnected: Boolean,
    onPreviewConnectionChange: (Boolean) -> Unit,
    vpnStatus: String,
    isConnecting: Boolean,
    vpnTimeMillis: Long,
    adsController: AdsController,
    adsEnabled: Boolean,
    adCooldownMillis: Long,
    adActionMessage: String,
    onAdsEnabledChange: (Boolean) -> Unit,
    onWatchAd: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverCatalog by remember { mutableStateOf(ServerCatalogStore.read(context)) }
    var payloadTemplates by remember { mutableStateOf(loadRemotePayloadTemplates(context)) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateStatus by rememberSaveable { mutableStateOf("Checking GitHub config and server sources…") }
    var serverActionStatus by rememberSaveable { mutableStateOf("") }
    var payloadActionStatus by rememberSaveable { mutableStateOf("") }
    var serverSearch by rememberSaveable { mutableStateOf("") }
    var profiles by remember { mutableStateOf(ProfileStore.profiles(context)) }
    var activeProfileId by remember { mutableStateOf(ProfileStore.activeProfileId(context)) }
    val filteredServers = serverCatalog.items.filter { item ->
        serverSearch.isBlank() || listOf(item.name,item.protocol,item.host,item.country,item.carrier,item.payloadName)
            .any { it.contains(serverSearch.trim(), ignoreCase = true) }
    }
    val mobivpnPayloads = serverCatalog.items.filter { it.payloadTemplate.isNotBlank() }
    val mobivpnXrayItems = serverCatalog.items.filter { it.shareText.isNotBlank() || it.configJson.isNotBlank() }

    suspend fun checkForUpdates() {
        if (checkingUpdates) return
        checkingUpdates = true
        val messages = mutableListOf<String>()
        try {
            try {
                val result = RemoteConfigStore.update(context)
                payloadTemplates = loadRemotePayloadTemplates(context)
                messages += result
            } catch (error: Exception) {
                payloadTemplates = loadRemotePayloadTemplates(context)
                messages += "Config update unavailable: ${error.message ?: "network error"}"
            }
            try {
                serverCatalog = ServerCatalogStore.refresh(context)
                messages += "Server list: ${serverCatalog.items.size} entries"
            } catch (error: Exception) {
                serverCatalog = ServerCatalogStore.read(context)
                messages += "Server update unavailable: ${error.message ?: "network error"}"
            }
            updateStatus = messages.joinToString("  •  ")
        } finally {
            checkingUpdates = false
        }
    }

    fun reloadProfiles() {
        profiles = ProfileStore.profiles(context)
        activeProfileId = ProfileStore.activeProfileId(context)
    }

    LaunchedEffect(Unit) {
        checkForUpdates()
    }

    ScreenColumn(padding) {
        CrashDiagnosticsCard()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("KUPAL VPN", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10271F)),
                shape = RoundedCornerShape(50)
            ) {
                Text("SECURE", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().border(1.dp, GlassBorder, RoundedCornerShape(26.dp)),
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(26.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Text(
                    vpnStatus.uppercase(),
                    color = if (previewConnected) Green else if (isConnecting) Color(0xFFFFD27A) else Color.LightGray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Image(
                    painter = painterResource(if (previewConnected) R.drawable.connected_status_button else R.drawable.disconnected_status_button),
                    contentDescription = if (previewConnected) "VPN connected. Tap to disconnect." else if (isConnecting) "VPN is connecting." else "VPN disconnected. Tap to connect.",
                    modifier = Modifier
                        .size(112.dp)
                        .clickable(enabled = previewConnected || (!isConnecting && vpnTimeMillis > 0L)) { onPreviewConnectionChange(!previewConnected) }
                )
                Text("VPN TIME  ${formatCountdown(vpnTimeMillis)}", color = if (vpnTimeMillis > 0L) Green else Color(0xFFFFD27A), fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = { onPreviewConnectionChange(!previewConnected) },
                    enabled = previewConnected || (!isConnecting && vpnTimeMillis > 0L),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (previewConnected) "Disconnect" else if (isConnecting) "Connecting…" else if (vpnTimeMillis > 0L) "Connect VPN" else "Earn VPN time to connect")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("DOWNLOAD", "0 KB/s", Modifier.weight(1f))
            MetricCard("UPLOAD", "0 KB/s", Modifier.weight(1f))
            MetricCard("LATENCY", "-- ms", Modifier.weight(1f))
        }

        RewardAccessCard(
            adsController = adsController,
            adsEnabled = adsEnabled,
            remainingMillis = adCooldownMillis,
            vpnTimeMillis = vpnTimeMillis,
            actionMessage = adActionMessage,
            onEnabledChange = onAdsEnabledChange,
            onShowAd = onWatchAd
        )
        if (adsEnabled && adsController.adsReady) HomeBannerAd(adsController)

        // MobiVPN-inspired Home layout: compact selectable server and config rows.
        SectionCard("SERVER LIST") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${profiles.size} profiles • ${serverCatalog.items.size} servers", color = Color.LightGray, fontSize = 11.sp)
                }
                Text(if (checkingUpdates) "SYNCING…" else "LIVE LIST", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            // Saved local server profiles appear first, like the selected-server list in MobiVPN.
            profiles.forEach { profile ->
                val selected = profile.id == activeProfileId
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        ProfileStore.select(context, profile.id)
                        reloadProfiles()
                    },
                    colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF15382F) else Color(0x80121216)),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(34.dp).border(1.dp, if (selected) Green else GlassBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Text(if (selected) "✓" else "◉", color = if (selected) Green else Color.LightGray, fontSize = 15.sp) }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(profile.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                listOf(profile.carrier, profile.protocol, profile.server).filter { it.isNotBlank() }.joinToString(" • "),
                                color = Color.LightGray,
                                fontSize = 10.sp,
                                maxLines = 2
                            )
                        }
                        Text(if (selected) "SELECTED" else "SELECT", color = if (selected) Green else Color.LightGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text("ADD CARRIER", color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("DITO", "Smart", "TNT", "Globe", "TM").forEach { carrier ->
                    OutlinedButton(
                        onClick = {
                            val apn = when (carrier) {
                                "DITO" -> "internet.dito.ph"
                                "Globe", "TM" -> "internet.globe.com.ph"
                                else -> "internet"
                            }
                            val id = ProfileStore.add(context, carrier, apn)
                            reloadProfiles()
                            ProfileStore.select(context, id)
                            reloadProfiles()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 7.dp)
                    ) { Text(carrier, fontSize = 9.sp) }
                }
            }
            Divider(color = GlassBorder)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("MOBIVPN SERVERS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
                Text("${filteredServers.size} / ${serverCatalog.items.size}", fontSize = 10.sp, color = Color.LightGray)
            }
            OutlinedTextField(
                value = serverSearch,
                onValueChange = { serverSearch = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search servers") }
            )
            if (serverCatalog.items.isEmpty()) {
                Text("No servers available.", color = Color.LightGray, fontSize = 11.sp)
            } else if (filteredServers.isEmpty()) {
                Text("No matching servers.", color = Color.LightGray, fontSize = 11.sp)
            } else {
                filteredServers.take(60).forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0x80121216)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text(
                                    listOf(item.protocol, item.carrier, item.country, if (item.host.isBlank()) "" else if (item.port > 0) "${item.host}:${item.port}" else item.host)
                                        .filter { it.isNotBlank() }.joinToString(" • "),
                                    color = Color.LightGray,
                                    fontSize = 10.sp
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    serverActionStatus = runCatching { ServerCatalogStore.useServer(context, item) }
                                        .getOrElse { "Could not select server: ${it.message ?: "unsupported entry"}" }
                                    reloadProfiles()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) { Text("USE", fontSize = 10.sp) }
                        }
                    }
                }
            }
            Button(
                onClick = { scope.launch { checkForUpdates() } },
                enabled = !checkingUpdates,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (checkingUpdates) "Updating lists…" else "Refresh server & config lists") }
            if (updateStatus.isNotBlank()) Text(updateStatus, color = if (updateStatus.contains("unavailable", true)) Color(0xFFFFD27A) else Green, fontSize = 10.sp)
            if (serverActionStatus.isNotBlank()) Text(serverActionStatus, color = if (serverActionStatus.contains("could not", true)) Color(0xFFFFD27A) else Green, fontSize = 11.sp)
        }

        var selectedPayloadName by rememberSaveable { mutableStateOf("Default HTTP CONNECT") }
        SectionCard("CONFIG LIST") {
            payloadTemplates.forEach { (name, template) ->
                    val selected = selectedPayloadName == name
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            SshConfigStore.savePayload(context, template)
                            selectedPayloadName = name
                            payloadActionStatus = "Selected config: $name"
                        },
                        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF15382F) else Color(0x80121216)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(30.dp).border(1.dp, if (selected) Green else GlassBorder, CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Text(if (selected) "✓" else "≡", color = if (selected) Green else Color.LightGray) }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(if (selected) "ACTIVE" else "SELECT", color = if (selected) Green else Color.LightGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
            }
            mobivpnPayloads.forEach { item ->
                val name = item.payloadName.ifBlank { item.name }
                val selected = selectedPayloadName == name
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        SshConfigStore.savePayload(context, item.payloadTemplate)
                        selectedPayloadName = name
                        payloadActionStatus = "Selected MobiVPN payload: $name"
                    },
                    colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF15382F) else Color(0x80121216)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("SSH • HTTP payload", fontSize = 10.sp, color = Color.LightGray)
                        }
                        Text(if (selected) "ACTIVE" else "SELECT", color = if (selected) Green else Color.LightGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            mobivpnXrayItems.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Xray • ${item.protocol}", fontSize = 10.sp, color = Color.LightGray)
                    }
                    OutlinedButton(
                        onClick = {
                            serverActionStatus = runCatching { ServerCatalogStore.useServer(context, item) }
                                .getOrElse { "Could not import config: ${it.message ?: "unsupported entry"}" }
                            reloadProfiles()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("IMPORT", fontSize = 9.sp) }
                }
            }
            if (payloadTemplates.isEmpty() && mobivpnPayloads.isEmpty() && mobivpnXrayItems.isEmpty()) {
                Text("No configs available.", color = Color.LightGray, fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = {
                    SshConfigStore.savePayload(context, SshConfigStore.DEFAULT_PAYLOAD)
                    selectedPayloadName = "Default HTTP CONNECT"
                    payloadActionStatus = "Built-in HTTP CONNECT config selected."
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (selectedPayloadName == "Default HTTP CONNECT") "✓ Default HTTP CONNECT" else "Use default HTTP CONNECT") }
            if (payloadActionStatus.isNotBlank()) Text(payloadActionStatus, color = Green, fontSize = 11.sp)
        }

    }
}

private const val HOME_BANNER_AD_UNIT_ID = "ca-app-pub-9471897591250086/8384504352"
private const val PRIVACY_POLICY_URL = "https://github.com/darwindiolimbo-crypto/kupal-vpn/blob/main/PRIVACY_POLICY.md"

@Composable
private fun HomeBannerAd(adsController: AdsController) {
    val activity = LocalContext.current as? Activity ?: return
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.coerceAtLeast(1)
    val createdAdView = remember(activity) { mutableStateOf<AdView?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            runCatching {
                AdView(context).apply {
                    adUnitId = HOME_BANNER_AD_UNIT_ID
                    setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, screenWidthDp))
                    var bannerRetryDelayMs = 30_000L
                    val bannerView = this
                    adListener = object : AdListener() {
                        override fun onAdLoaded() {
                            bannerRetryDelayMs = 30_000L
                            adsController.reportBannerLoaded()
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            android.util.Log.w("KupalVPN", "Banner ad unavailable: ${error.message}")
                            adsController.reportBannerFailed(error)
                            val retryDelay = bannerRetryDelayMs
                            bannerRetryDelayMs = (bannerRetryDelayMs * 2L).coerceAtMost(5L * 60L * 1000L)
                            bannerView.postDelayed({
                                if (bannerView.parent != null && !activity.isFinishing && !activity.isDestroyed) {
                                    runCatching {
                                        bannerView.loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
                                    }.onFailure {
                                        android.util.Log.w("KupalVPN", "Banner retry failed", it)
                                    }
                                }
                            }, retryDelay)
                        }
                    }
                    createdAdView.value = this
                    loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
                }
            }.getOrElse { error ->
                // A banner is optional: never let a vendor SDK/view failure crash Home.
                android.util.Log.e("KupalVPN", "Banner view could not be created", error)
                android.widget.FrameLayout(context)
            }
        },
        update = { view ->
            (view as? AdView)?.let { banner ->
                runCatching {
                    banner.setAdSize(
                        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, screenWidthDp)
                    )
                }.onFailure {
                    android.util.Log.w("KupalVPN", "Banner size update failed", it)
                }
            }
        }
    )
    DisposableEffect(activity) {
        onDispose {
            createdAdView.value?.let { banner ->
                runCatching { banner.destroy() }
                    .onFailure { android.util.Log.w("KupalVPN", "Banner cleanup failed", it) }
            }
            createdAdView.value = null
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.border(1.dp, GlassBorder, RoundedCornerShape(16.dp)), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 10.sp, color = Color.LightGray)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.border(1.dp, GlassBorder, RoundedCornerShape(18.dp)), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            content()
        }
    }
}

@Composable
private fun ConfigScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val profileId = ProfileStore.activeProfileId(context)
    var tunnelMode by rememberSaveable(profileId) { mutableStateOf(TunnelModeStore.readMode(context)) }
    val savedSsh = remember(profileId) { SshConfigStore.read(context) }
    var payloadTemplate by rememberSaveable(profileId) { mutableStateOf(savedSsh.payload) }
    var sshMode by rememberSaveable(profileId) { mutableStateOf(savedSsh.mode) }
    var sshHost by rememberSaveable(profileId) { mutableStateOf(savedSsh.host) }
    var sshPort by rememberSaveable(profileId) { mutableStateOf(savedSsh.port.toString()) }
    var sshUsername by rememberSaveable(profileId) { mutableStateOf(savedSsh.username) }
    var sshPassword by remember(profileId) { mutableStateOf(savedSsh.password) }
    var privateKey by remember(profileId) { mutableStateOf(savedSsh.privateKey) }
    var privateKeyPassphrase by remember(profileId) { mutableStateOf(savedSsh.privateKeyPassphrase) }
    var proxyHost by rememberSaveable(profileId) { mutableStateOf(savedSsh.proxyHost) }
    var proxyPort by rememberSaveable(profileId) { mutableStateOf(savedSsh.proxyPort.toString()) }
    var sniHost by rememberSaveable(profileId) { mutableStateOf(savedSsh.sniHost) }
    var udpGatewayPort by rememberSaveable(profileId) { mutableStateOf(savedSsh.udpGatewayPort.toString()) }
    var saveMessage by rememberSaveable(profileId) { mutableStateOf("") }
    var jsonText by rememberSaveable(profileId) { mutableStateOf(LocalStore.readJson(context)) }
    var validation by rememberSaveable(profileId) { mutableStateOf("Configuration not validated") }
    val privateKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Cannot read the selected key file")
                require(bytes.isNotEmpty() && bytes.size <= 128 * 1024) { "Private key file must be between 1 byte and 128 KB" }
                val text = String(bytes, Charsets.UTF_8)
                require(text.contains("PRIVATE KEY")) { "Selected file does not look like a PEM/OpenSSH private key" }
                privateKey = text
                saveMessage = "Private key imported. Tap Save SSH configuration to store it securely."
            } catch (error: Exception) {
                saveMessage = error.message ?: "Could not import private key"
            }
        }
    }

    ScreenColumn(padding) {
        Text("Connection engine", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(TunnelModeStore.MODE_XRAY, TunnelModeStore.MODE_SSH).forEach { mode ->
                if (tunnelMode == mode) {
                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(mode) }
                } else {
                    OutlinedButton(
                        onClick = {
                            tunnelMode = mode
                            TunnelModeStore.saveMode(context, mode)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(mode) }
                }
            }
        }

        if (tunnelMode == TunnelModeStore.MODE_SSH) {
            SectionCard("SSH server") {
                
                OutlinedTextField(sshHost, { sshHost = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH server / host") }, singleLine = true)
                OutlinedTextField(sshPort, { sshPort = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(sshUsername, { sshUsername = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH username") }, singleLine = true)
                OutlinedTextField(sshPassword, { sshPassword = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH password (optional when key is used)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
            SectionCard("SSH authentication key") {
                
                Button(onClick = { privateKeyPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (privateKey.isBlank()) "Import private key" else "Replace private key")
                }
                Text(if (privateKey.isBlank()) "No private key imported" else "Private key imported for this profile", color = if (privateKey.isBlank()) Color.LightGray else Green, fontSize = 12.sp)
                OutlinedTextField(
                    privateKeyPassphrase,
                    { privateKeyPassphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Private key passphrase (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (privateKey.isNotBlank()) {
                    OutlinedButton(onClick = { privateKey = ""; privateKeyPassphrase = "" }, modifier = Modifier.fillMaxWidth()) {
                        Text("Remove imported key")
                    }
                }
            }
            SectionCard("SSH connection type") {
                val modes = listOf(
                    SshConfig.MODE_DIRECT to "Direct TCP",
                    SshConfig.MODE_HTTP_PAYLOAD_DIRECT to "HTTP Payload",
                    SshConfig.MODE_HTTP_PAYLOAD_PROXY to "HTTP Payload Proxy",
                    SshConfig.MODE_TLS_PAYLOAD_DIRECT to "TLS + SNI Direct",
                    SshConfig.MODE_TLS_PAYLOAD_PROXY to "TLS + SNI via Proxy"
                )
                modes.forEach { (value, label) ->
                    val selected = sshMode == value
                    if (selected) {
                        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { sshMode = value }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }
            if (sshMode == SshConfig.MODE_HTTP_PAYLOAD_PROXY || sshMode == SshConfig.MODE_TLS_PAYLOAD_PROXY) {
                SectionCard("HTTP proxy") {
                    OutlinedTextField(proxyHost, { proxyHost = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Proxy host / IP") }, singleLine = true)
                    OutlinedTextField(proxyPort, { proxyPort = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(), label = { Text("Proxy port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            if (sshMode == SshConfig.MODE_TLS_PAYLOAD_DIRECT || sshMode == SshConfig.MODE_TLS_PAYLOAD_PROXY) {
                SectionCard("TLS / SNI") {
                    OutlinedTextField(sniHost, { sniHost = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SNI host name") }, singleLine = true)
                }
            }
            SectionCard("HTTP payload") {
                
                OutlinedTextField(
                    value = payloadTemplate,
                    onValueChange = { payloadTemplate = it },
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                    label = { Text("SSH HTTP payload") },
                    minLines = 6,
                    maxLines = 12,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
                )
                OutlinedButton(
                    onClick = {
                        SshConfigStore.savePayload(context, payloadTemplate)
                        saveMessage = "SSH payload saved for this profile."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save payload") }
            }
            Button(
                onClick = {
                    val next = SshConfig(
                        mode = sshMode,
                        host = sshHost.trim(),
                        port = sshPort.toIntOrNull() ?: 0,
                        username = sshUsername.trim(),
                        password = sshPassword,
                        privateKey = privateKey,
                        privateKeyPassphrase = privateKeyPassphrase,
                        proxyHost = proxyHost.trim(),
                        proxyPort = proxyPort.toIntOrNull() ?: 0,
                        sniHost = sniHost.trim(),
                        udpGatewayPort = udpGatewayPort.toIntOrNull() ?: 0,
                        payload = payloadTemplate
                    )
                    try {
                        next.validate()
                        SshConfigStore.save(context, next)
                        TunnelModeStore.saveMode(context, TunnelModeStore.MODE_SSH)
                        saveMessage = "SSH settings saved for this profile."
                    } catch (error: Exception) {
                        saveMessage = error.message ?: "Check the SSH settings."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save SSH configuration") }
            if (saveMessage.isNotBlank()) Text(saveMessage, color = if (saveMessage.contains("saved")) Green else Color(0xFFFFD27A), fontSize = 12.sp)
        } else {
            Text("Xray configuration", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            
            OutlinedTextField(
                value = jsonText,
                onValueChange = {
                    jsonText = it
                    LocalStore.writeJson(context, it)
                    validation = "Unsaved changes"
                },
                modifier = Modifier.fillMaxWidth().height(420.dp),
                label = { Text("Xray JSON") },
                minLines = 14,
                maxLines = 30,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    validation = try {
                        XrayConfigValidator.validate(jsonText)
                        "Valid Xray server configuration"
                    } catch (error: Exception) {
                        error.message ?: "Invalid Xray configuration"
                    }
                }) { Text("Validate server") }
                OutlinedButton(onClick = {
                    jsonText = LocalStore.readJson(context)
                    validation = "Draft reloaded"
                }) { Text("Reload saved") }
            }
            Text(validation, color = if (validation.startsWith("Valid")) Green else Color(0xFFFFD27A))
            
        }
    }
}

private fun loadRemotePayloadTemplates(context: android.content.Context): List<Pair<String, String>> {
    val array = RemoteConfigStore.read(context).optJSONArray("payloadTemplates") ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val name = item.optString("name")
        val payload = item.optString("payload")
        if (name.isBlank() || payload.isBlank()) null else name to payload
    }
}

@Composable
private fun RewardAccessCard(
    adsController: AdsController,
    adsEnabled: Boolean,
    remainingMillis: Long,
    vpnTimeMillis: Long,
    actionMessage: String,
    onEnabledChange: (Boolean) -> Unit,
    onShowAd: () -> Unit
) {
    val cooldownFinished = remainingMillis <= 0L
    SectionCard("Earn VPN time") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                
                Text(formatCountdown(vpnTimeMillis), fontSize = 25.sp, fontWeight = FontWeight.Bold, color = if (vpnTimeMillis > 0L) Green else Color.White)
            }
            Switch(checked = adsEnabled, onCheckedChange = onEnabledChange)
        }
        Text("+2 hours for each completed rewarded video", fontWeight = FontWeight.SemiBold, color = Green, fontSize = 13.sp)
        Text(
            if (!adsEnabled) "Rewarded ads are paused"
            else if (!cooldownFinished) "Next video available in ${formatCountdown(remainingMillis)}"
            else if (adsController.rewardedReady) "A rewarded video is ready"
            else adsController.lastStatus,
            color = Color.LightGray,
            fontSize = 12.sp
        )
        Button(onClick = onShowAd, enabled = adsEnabled && cooldownFinished, modifier = Modifier.fillMaxWidth()) {
            Text(if (adsController.rewardedReady) "Watch ad · Add 2 hours" else "Load / retry rewarded ad")
        }
        if (actionMessage.isNotBlank()) Text(actionMessage, color = Green, fontSize = 12.sp)
        
    }
}

@Composable
private fun SettingsScreen(
    padding: PaddingValues,
    onPrivacyOptions: () -> Unit,
    privacyOptionsRequired: Boolean,
 ) {
    val context = LocalContext.current
    ScreenColumn(padding) {
        Text("Settings", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        SectionCard("Privacy") {
            if (privacyOptionsRequired) {
                OutlinedButton(onClick = onPrivacyOptions, modifier = Modifier.fillMaxWidth()) {
                    Text("Privacy options")
                }
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_POLICY_URL)))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Privacy policy") }
        }
    }
}

private fun formatCountdown(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainingSeconds = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remainingSeconds)
}


private fun validateVpnConfiguration(context: android.content.Context): String? =
    runCatching {
        if (TunnelModeStore.readMode(context) == TunnelModeStore.MODE_SSH) {
            SshConfigStore.read(context).validate()
        } else {
            XrayConfigValidator.validate(LocalStore.readJson(context))
        }
        null
    }.getOrElse { it.message ?: "Connection settings are incomplete" }

private fun saveVpnUiState(context: android.content.Context, state: String) {
    context.getSharedPreferences(KupalVpnService.STATE_PREFS, android.content.Context.MODE_PRIVATE)
        .edit()
        .putString(KupalVpnService.STATE_KEY, state)
        .putLong(KupalVpnService.STATE_UPDATED_KEY, System.currentTimeMillis())
        .apply()
}

private fun readEffectiveVpnState(context: android.content.Context): String {
    val prefs = context.getSharedPreferences(KupalVpnService.STATE_PREFS, android.content.Context.MODE_PRIVATE)
    val saved = prefs.getString(KupalVpnService.STATE_KEY, "disconnected") ?: "disconnected"
    val updatedAt = prefs.getLong(KupalVpnService.STATE_UPDATED_KEY, 0L)
    val staleConnecting = saved == "connecting" &&
        (updatedAt <= 0L || System.currentTimeMillis() - updatedAt > 60_000L)
    val vpnTransportActive = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.let { manager ->
            manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        }
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)
    val staleConnected = saved == "connected" && !vpnTransportActive

    if (staleConnecting || staleConnected) {
        runCatching { LocalStore.pauseVpnTime(context) }
        prefs.edit()
            .putString(KupalVpnService.STATE_KEY, "disconnected")
            .putLong(KupalVpnService.STATE_UPDATED_KEY, System.currentTimeMillis())
            .apply()
        return "disconnected"
    }
    return saved
}
