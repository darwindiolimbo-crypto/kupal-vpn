package com.kupalvpn.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
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
        adsController = AdsController(this)
        adsController.requestConsentAndInitialize()

        setContent {
            KupalVpnTheme {
                KupalVpnApp(
                    adsController = adsController,
                    onPrivacyOptions = { adsController.showPrivacyOptions() }
                )
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
    var vpnState by remember { mutableStateOf(
        context.getSharedPreferences(KupalVpnService.STATE_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KupalVpnService.STATE_KEY, "disconnected") ?: "disconnected"
    ) }
    var pendingVpnConfig by remember { mutableStateOf<String?>(null) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val config = pendingVpnConfig
        pendingVpnConfig = null
        if (result.resultCode == Activity.RESULT_OK && config != null) {
            val serviceIntent = Intent(context, KupalVpnService::class.java)
                .setAction(KupalVpnService.ACTION_START)
                .putExtra(KupalVpnService.EXTRA_CONFIG, config)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        }
    }
    val previewConnected = vpnState == "connected" || vpnState == "connecting"
    fun requestVpnToggle() {
        if (previewConnected) {
            context.startService(Intent(context, KupalVpnService::class.java).setAction(KupalVpnService.ACTION_STOP))
        } else {
            pendingVpnConfig = LocalStore.readJson(context)
            val permissionIntent = VpnService.prepare(context)
            if (permissionIntent == null) {
                val serviceIntent = Intent(context, KupalVpnService::class.java)
                    .setAction(KupalVpnService.ACTION_START)
                    .putExtra(KupalVpnService.EXTRA_CONFIG, pendingVpnConfig)
                if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent)
                else context.startService(serviceIntent)
                pendingVpnConfig = null
            } else vpnPermissionLauncher.launch(permissionIntent)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            vpnState = context.getSharedPreferences(KupalVpnService.STATE_PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KupalVpnService.STATE_KEY, "disconnected") ?: "disconnected"
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
                listOf("Home", "Profiles", "Config", "Payload", "Settings").forEach { destination ->
                    val icon = when (destination) {
                        "Home" -> Icons.Default.Home
                        "Profiles" -> Icons.Default.Settings
                        "Config" -> Icons.Default.Code
                        "Payload" -> Icons.Default.Tune
                        "Free Servers" -> Icons.Default.Settings
                        else -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = tab == destination,
                        onClick = {
                            // An interstitial is never auto-shown just because a timer expires.
                            tab = destination
                        },
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
            "Profiles" -> ProfilesScreen(padding = padding)
            "Config" -> ConfigScreen(padding = padding)
            "Payload" -> PayloadScreen(padding = padding)
            else -> SettingsScreen(padding = padding, onPrivacyOptions = onPrivacyOptions)
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
    vpnTimeMillis: Long,
    adsController: AdsController,
    adsEnabled: Boolean,
    adCooldownMillis: Long,
    adActionMessage: String,
    onAdsEnabledChange: (Boolean) -> Unit,
    onWatchAd: () -> Unit
) {
    ScreenColumn(padding) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("KUPAL VPN", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
                Text("Private connection • Your profiles", color = Color.LightGray, fontSize = 12.sp)
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
                    color = if (previewConnected) Green else Color.LightGray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Image(
                    painter = painterResource(if (previewConnected) R.drawable.connected_status_button else R.drawable.disconnected_status_button),
                    contentDescription = if (previewConnected) "VPN connected. Tap to disconnect." else "VPN disconnected. Tap to connect.",
                    modifier = Modifier
                        .size(176.dp)
                        .clickable(enabled = previewConnected || vpnTimeMillis > 0L) { onPreviewConnectionChange(!previewConnected) }
                )
                Text("VPN TIME  ${formatCountdown(vpnTimeMillis)}", color = if (vpnTimeMillis > 0L) Green else Color(0xFFFFD27A), fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = { onPreviewConnectionChange(!previewConnected) },
                    enabled = previewConnected || vpnTimeMillis > 0L,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (previewConnected) "Disconnect" else if (vpnTimeMillis > 0L) "Connect VPN" else "Earn VPN time to connect")
                }
                Text(
                    "Choose an SSH or Xray profile and save its server settings before connecting.",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
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
        SectionCard("Quick status") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Engines", color = Color.LightGray)
                Text("SSH • Xray", fontWeight = FontWeight.SemiBold)
            }
            Text("Rewarded time is saved on this device and is deducted only while the VPN is connected.", color = Color.LightGray, fontSize = 12.sp)
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
    var sshMode by rememberSaveable(profileId) { mutableStateOf(savedSsh.mode) }
    var sshHost by rememberSaveable(profileId) { mutableStateOf(savedSsh.host) }
    var sshPort by rememberSaveable(profileId) { mutableStateOf(savedSsh.port.toString()) }
    var sshUsername by rememberSaveable(profileId) { mutableStateOf(savedSsh.username) }
    var sshPassword by rememberSaveable(profileId) { mutableStateOf(savedSsh.password) }
    var proxyHost by rememberSaveable(profileId) { mutableStateOf(savedSsh.proxyHost) }
    var proxyPort by rememberSaveable(profileId) { mutableStateOf(savedSsh.proxyPort.toString()) }
    var sniHost by rememberSaveable(profileId) { mutableStateOf(savedSsh.sniHost) }
    var saveMessage by rememberSaveable(profileId) { mutableStateOf("") }
    var jsonText by rememberSaveable(profileId) { mutableStateOf(LocalStore.readJson(context)) }
    var validation by rememberSaveable(profileId) { mutableStateOf("Configuration not validated") }

    ScreenColumn(padding) {
        Text("Connection engine", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Choose the tunnel protocol for this profile.", color = Color.LightGray, fontSize = 13.sp)
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
                Text("SSH credentials and HTTP transport are stored per profile on this device.", color = Color.LightGray, fontSize = 12.sp)
                OutlinedTextField(sshHost, { sshHost = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH server / host") }, singleLine = true)
                OutlinedTextField(sshPort, { sshPort = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(sshUsername, { sshUsername = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH username") }, singleLine = true)
                OutlinedTextField(sshPassword, { sshPassword = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
            SectionCard("SSH connection type") {
                val modes = listOf(
                    SshConfig.MODE_DIRECT to "Direct TCP",
                    SshConfig.MODE_HTTP_PAYLOAD_DIRECT to "HTTP Payload",
                    SshConfig.MODE_HTTP_PAYLOAD_PROXY to "HTTP Payload Proxy",
                    SshConfig.MODE_TLS_PAYLOAD_PROXY to "TLS + SNI Payload"
                )
                modes.forEach { (value, label) ->
                    val selected = sshMode == value
                    if (selected) {
                        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { sshMode = value }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
                Text(
                    "HTTP is a transport/payload option for SSH. It is not a separate VPN engine.",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            }
            if (sshMode == SshConfig.MODE_HTTP_PAYLOAD_PROXY || sshMode == SshConfig.MODE_TLS_PAYLOAD_PROXY) {
                SectionCard("HTTP proxy") {
                    OutlinedTextField(proxyHost, { proxyHost = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Proxy host / IP") }, singleLine = true)
                    OutlinedTextField(proxyPort, { proxyPort = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(), label = { Text("Proxy port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            if (sshMode == SshConfig.MODE_TLS_PAYLOAD_PROXY) {
                SectionCard("TLS / SNI") {
                    OutlinedTextField(sniHost, { sniHost = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SNI host name") }, singleLine = true)
                }
            }
            SectionCard("Payload") {
                Text("Edit the HTTP request in the Payload tab. Supported tokens: [host], [port], [host_port], [crlf].", color = Color.LightGray, fontSize = 12.sp)
                OutlinedButton(onClick = { saveMessage = "Open the Payload tab to edit the SSH payload." }, modifier = Modifier.fillMaxWidth()) {
                    Text("Payload editor is in the Payload tab")
                }
            }
            Button(
                onClick = {
                    val next = SshConfig(
                        mode = sshMode,
                        host = sshHost.trim(),
                        port = sshPort.toIntOrNull() ?: 0,
                        username = sshUsername.trim(),
                        password = sshPassword,
                        proxyHost = proxyHost.trim(),
                        proxyPort = proxyPort.toIntOrNull() ?: 0,
                        sniHost = sniHost.trim(),
                        payload = SshConfigStore.read(context).payload
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
            Text("Edit a VLESS, VMess, Trojan or Shadowsocks Xray JSON profile.", color = Color.LightGray, fontSize = 13.sp)
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
                        JSONObject(jsonText)
                        "Valid JSON syntax"
                    } catch (error: Exception) {
                        "Invalid JSON: ${error.message ?: "parse error"}"
                    }
                }) { Text("Validate JSON") }
                OutlinedButton(onClick = {
                    jsonText = LocalStore.readJson(context)
                    validation = "Draft reloaded"
                }) { Text("Reload saved") }
            }
            Text(validation, color = if (validation.startsWith("Valid")) Green else Color(0xFFFFD27A))
            Text("Use authorized server values. Never share your UUID or private keys publicly.", color = Color.LightGray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PayloadScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val profileId = ProfileStore.activeProfileId(context)
    val scope = rememberCoroutineScope()
    var payload by rememberSaveable(profileId) { mutableStateOf(SshConfigStore.read(context).payload) }
    var host by rememberSaveable(profileId) { mutableStateOf(SshConfigStore.read(context).host) }
    var port by rememberSaveable(profileId) { mutableStateOf(SshConfigStore.read(context).port.toString()) }
    var result by rememberSaveable(profileId) { mutableStateOf("SSH payload editor ready") }
    var updating by remember { mutableStateOf(false) }
    var templates by remember(profileId) { mutableStateOf(loadRemotePayloadTemplates(context)) }

    val preview = payload
        .replace("[host_port]", if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port")
        .replace("[host]", host)
        .replace("[port]", port)
        .replace("[crlf]", "\r\n")
        .replace("\\r\\n", "\r\n")
        .replace("\\n", "\r\n")

    ScreenColumn(padding) {
        Text("SSH payload studio", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("HTTP payloads are used by the SSH connection engine. They are not a separate HTTP VPN mode.", color = Color.LightGray, fontSize = 13.sp)
        SectionCard("GitHub payload catalog") {
            Text("Download the latest safe templates and DNS presets from the Kupal VPN config repository.", color = Color.LightGray, fontSize = 12.sp)
            Button(
                onClick = {
                    scope.launch {
                        updating = true
                        result = "Checking GitHub for configuration updates…"
                        try {
                            result = RemoteConfigStore.update(context)
                            templates = loadRemotePayloadTemplates(context)
                        } catch (error: Exception) {
                            result = error.message ?: "Config update failed"
                        } finally {
                            updating = false
                        }
                    }
                },
                enabled = !updating,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (updating) "Updating…" else "Update templates from GitHub") }
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(RemoteConfigStore.EDIT_URL)))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Edit payload catalog on GitHub") }
            if (templates.isEmpty()) {
                Text("No remote templates cached yet. Tap Update templates from GitHub.", fontSize = 12.sp, color = Color.LightGray)
            } else {
                templates.forEach { (name, template) ->
                    OutlinedButton(
                        onClick = {
                            payload = template
                            val current = SshConfigStore.read(context)
                            SshConfigStore.save(context, current.copy(payload = template))
                            result = "Loaded template: $name"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("＋ $name") }
                }
            }
        }
        SectionCard("Request payload") {
            OutlinedTextField(
                value = payload,
                onValueChange = {
                    payload = it
                    val current = SshConfigStore.read(context)
                    SshConfigStore.save(context, current.copy(payload = it))
                    result = "Draft saved for the active profile"
                },
                modifier = Modifier.fillMaxWidth().height(220.dp),
                label = { Text("HTTP / CONNECT payload") },
                minLines = 7,
                maxLines = 14,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            Text("Tokens: [host], [port], [host_port], [crlf]", color = Green, fontSize = 12.sp)
        }
        SectionCard("Preview target") {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("SSH server host") },
                singleLine = true
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("SSH server port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text("Preview only. The live SSH endpoint is saved from Config → SSH server.", fontSize = 11.sp, color = Color.LightGray)
        }
        SectionCard("Rendered payload") {
            Text(preview.replace("\r", "\\r").replace("\n", "\\n\n"), fontSize = 11.sp, color = Color(0xFFB8D8EF))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val current = SshConfigStore.read(context)
                SshConfigStore.save(context, current.copy(payload = payload))
                result = "Payload saved to the active SSH profile"
            }, modifier = Modifier.weight(1f)) { Text("Save") }
            OutlinedButton(onClick = {
                payload = SshConfigStore.DEFAULT_PAYLOAD
                val current = SshConfigStore.read(context)
                SshConfigStore.save(context, current.copy(payload = payload))
                result = "Default HTTP CONNECT template restored"
            }, modifier = Modifier.weight(1f)) { Text("Reset") }
        }
        Text(result, color = if (result.contains("failed", true)) Color(0xFFFFD27A) else Green, fontSize = 12.sp)
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
    val adReady = remainingMillis <= 0L
    SectionCard("Earn VPN time") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Available access", color = Color.LightGray, fontSize = 12.sp)
                Text(formatCountdown(vpnTimeMillis), fontSize = 25.sp, fontWeight = FontWeight.Bold, color = if (vpnTimeMillis > 0L) Green else Color.White)
            }
            Switch(checked = adsEnabled, onCheckedChange = onEnabledChange)
        }
        Text("+2 hours for each completed rewarded video", fontWeight = FontWeight.SemiBold, color = Green, fontSize = 13.sp)
        Text(
            if (!adsEnabled) "Rewarded ads are paused"
            else if (adReady) "A rewarded video is ready"
            else "Next video available in ${formatCountdown(remainingMillis)}",
            color = Color.LightGray,
            fontSize = 12.sp
        )
        Button(onClick = onShowAd, enabled = adsEnabled && adReady, modifier = Modifier.fillMaxWidth()) {
            Text("Watch ad · Add 2 hours")
        }
        if (actionMessage.isNotBlank()) Text(actionMessage, color = Green, fontSize = 12.sp)
        Text(adsController.lastStatus, color = Color.LightGray, fontSize = 10.sp)
        Text("10-second cooldown between ad starts. The reward is added only after AdMob confirms completion.", color = Color.LightGray, fontSize = 11.sp)
    }
}

@Composable
private fun SettingsScreen(
    padding: PaddingValues,
    onPrivacyOptions: () -> Unit
) {
    val context = LocalContext.current
    ScreenColumn(padding) {
        Text("Settings", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("App preferences, privacy and remote configuration.", color = Color.LightGray, fontSize = 13.sp)
        SectionCard("Privacy") {
            Text("Manage consent and advertising privacy choices.", color = Color.LightGray, fontSize = 12.sp)
            OutlinedButton(onClick = onPrivacyOptions, modifier = Modifier.fillMaxWidth()) {
                Text("Privacy & consent options")
            }
        }
        SectionCard("Configuration updates") {
            Text("Payload templates and DNS presets are updated from the public Kupal VPN GitHub catalog. Server credentials are never included in that catalog.", color = Color.LightGray, fontSize = 12.sp)
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(RemoteConfigStore.EDIT_URL))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Edit config catalog on GitHub") }
            Text(RemoteConfigStore.CONFIG_URL, color = Color(0xFF59B9E8), fontSize = 10.sp)
        }
        SectionCard("About Kupal VPN") {
            Text("Premium glass interface • SSH payload • Xray profiles", color = Color.LightGray, fontSize = 12.sp)
            Text("Never publish your SSH password, private key, or Xray UUID in the public config catalog.", color = Color(0xFFFFD27A), fontSize = 11.sp)
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
