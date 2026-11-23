package com.kupalvpn.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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

private val Navy = Color(0xFF07111E)
private val Panel = Color(0xFF102236)
private val Green = Color(0xFF21C58E)

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
    var previewConnected by rememberSaveable { mutableStateOf(false) }
    var adsEnabled by rememberSaveable { mutableStateOf(LocalStore.adsEnabled(context)) }
    var remaining by remember { mutableLongStateOf(adsController.remainingMillis()) }
    var adActionMessage by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(adsEnabled) {
        while (true) {
            remaining = adsController.remainingMillis()
            delay(1_000)
        }
    }

    Scaffold(
        containerColor = Navy,
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Home", "Config", "Payload", "Free Servers", "Ads").forEach { destination ->
                    val icon = when (destination) {
                        "Home" -> Icons.Default.Home
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
                onPreviewConnectionChange = { previewConnected = it }
            )
            "Config" -> ConfigScreen(padding = padding)
            "Payload" -> PayloadScreen(padding = padding)
            "Free Servers" -> FreeServersScreen(padding = padding)
            else -> AdsScreen(
                padding = padding,
                adsController = adsController,
                previewConnected = previewConnected,
                adsEnabled = adsEnabled,
                remainingMillis = remaining,
                actionMessage = adActionMessage,
                onEnabledChange = {
                    adsEnabled = it
                    LocalStore.setAdsEnabled(context, it)
                },
                onShowAd = {
                    val activity = context as? Activity
                    adActionMessage = if (activity == null) {
                        "Could not access the Activity"
                    } else {
                        adsController.tryShowDueInterstitial(previewConnected)
                    }
                },
                onPrivacyOptions = onPrivacyOptions
            )
        }
    }
}

@Composable
private fun ScreenColumn(padding: PaddingValues, content: @Composable () -> Unit) {
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
    onPreviewConnectionChange: (Boolean) -> Unit
) {
    ScreenColumn(padding) {
        Text("KUPAL VPN", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Xray • V2Ray-compatible profile manager", color = Color.LightGray)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(if (previewConnected) "PREVIEW CONNECTED" else "DISCONNECTED", color = if (previewConnected) Green else Color.LightGray)
                Box(
                    modifier = Modifier.size(128.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Surface(
                        modifier = Modifier.size(116.dp),
                        shape = CircleShape,
                        color = if (previewConnected) Color(0xFF176B52) else Color(0xFF19334D)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("⏻", fontSize = 54.sp, color = Color.White)
                        }
                    }
                }
                Button(
                    onClick = { onPreviewConnectionChange(!previewConnected) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (previewConnected) "Disconnect preview" else "Connect preview")
                }
                Text(
                    "UI preview only. The Xray tunnel engine is not connected in this starter project.",
                    color = Color(0xFFFFD27A),
                    fontSize = 12.sp
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("DOWNLOAD", "0 KB/s", Modifier.weight(1f))
            MetricCard("UPLOAD", "0 KB/s", Modifier.weight(1f))
            MetricCard("PING", "-- ms", Modifier.weight(1f))
        }
        SectionCard("Included modules") {
            Text("• Xray JSON editor with syntax validation")
            Text("• HTTP payload template editor and preview")
            Text("• Timer-gated AdMob test interstitial")
            Text("• UMP privacy consent and privacy options entry point")
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 10.sp, color = Color.LightGray)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            content()
        }
    }
}

@Composable
private fun ConfigScreen(padding: PaddingValues) {
    val context = LocalContext.current
    var jsonText by rememberSaveable { mutableStateOf(LocalStore.readJson(context)) }
    var validation by rememberSaveable { mutableStateOf("Draft not validated") }

    ScreenColumn(padding) {
        Text("Xray configuration", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Edit an Xray JSON profile. Validation checks JSON syntax only, not every Xray field.", color = Color.LightGray, fontSize = 13.sp)
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
        Text("Use your own server values. Never share your UUID, password, or private keys publicly.", color = Color.LightGray, fontSize = 12.sp)
    }
}

@Composable
private fun FreeServersScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val subscription = "https://radarhub.top/v2ray.txt"
    var message by rememberSaveable { mutableStateOf("Public subscription source. The app currently cannot import/connect until the Xray engine is integrated.") }

    ScreenColumn(padding) {
        Text("Free test servers", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Use public nodes temporarily for protocol and UI testing. Public nodes are run by unknown operators and can be unreliable.", color = Color.LightGray, fontSize = 13.sp)
        SectionCard("Free V2Ray / Xray-compatible subscription") {
            Text("Free Nodes Radar", fontWeight = FontWeight.SemiBold)
            Text("Publicly collected VLESS, VMess, Trojan, and Shadowsocks nodes. The list is refreshed periodically; individual nodes may be offline or unsafe.", color = Color.LightGray, fontSize = 13.sp)
            Text(subscription, color = Color(0xFF59B9E8), fontSize = 12.sp)
            Button(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Free V2Ray subscription", subscription))
                message = "Subscription URL copied. Subscription importing is not implemented yet."
            }, modifier = Modifier.fillMaxWidth()) { Text("Copy subscription URL") }
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Free V2Ray subscription", subscription))
                message = "Copied for use in a compatible VPN client."
            }, modifier = Modifier.fillMaxWidth()) { Text("Copy for external VPN client") }
        }
        SectionCard("Safety reminder") {
            Text("Avoid banking, email, or sensitive logins on unknown free servers. HTTPS protects webpage contents, but server operators may still observe connection metadata. These entries are not guaranteed to work in your region.", color = Color(0xFFFFD27A), fontSize = 12.sp)
        }
        Text(message, color = Green, fontSize = 12.sp)
        Text("The free server subscription is a source link, not a live VPN connection inside Kupal VPN yet.", color = Color.LightGray, fontSize = 12.sp)
    }
}


@Composable
private fun PayloadScreen(padding: PaddingValues) {
    val context = LocalContext.current
    var payload by rememberSaveable { mutableStateOf(LocalStore.readPayload(context)) }
    var host by rememberSaveable { mutableStateOf("your-proxy.example.com") }
    var port by rememberSaveable { mutableStateOf("443") }
    var result by rememberSaveable { mutableStateOf("Payload template loaded") }

    val preview = payload
        .replace("[host_port]", "$host:$port")
        .replace("[host]", host)
        .replace("[port]", port)
        .replace("[crlf]", "\r\n")

    ScreenColumn(padding) {
        Text("Payload & setup editor", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Create and save HTTP request templates. These are separate from Xray transport settings; they are not automatically injected into VLESS/VMess/Trojan.",
            color = Color.LightGray,
            fontSize = 13.sp
        )
        OutlinedTextField(
            value = payload,
            onValueChange = {
                payload = it
                LocalStore.writePayload(context, it)
                result = "Draft updated"
            },
            modifier = Modifier.fillMaxWidth().height(230.dp),
            label = { Text("HTTP payload template") },
            minLines = 8,
            maxLines = 14,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
        )
        Text("Supported placeholders: [host], [port], [host_port], [crlf]", color = Green, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.weight(1f),
                label = { Text("Host") },
                singleLine = true
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.width(104.dp),
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        SectionCard("Preview") {
            Text(preview.replace("\r", "\\r").replace("\n", "\\n\n"), fontSize = 11.sp, color = Color(0xFFB8D8EF))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                LocalStore.writePayload(context, payload)
                result = "Payload template saved on this device"
            }) { Text("Save template") }
            OutlinedButton(onClick = {
                payload = "CONNECT [host_port] HTTP/1.1[crlf]\nHost: [host][crlf]\nUser-Agent: KupalVPN/1.0[crlf]\nProxy-Connection: keep-alive[crlf][crlf]"
                LocalStore.writePayload(context, payload)
                result = "Default template restored"
            }) { Text("Reset") }
        }
        Text(result, color = Green, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdsScreen(
    padding: PaddingValues,
    adsController: AdsController,
    previewConnected: Boolean,
    adsEnabled: Boolean,
    remainingMillis: Long,
    actionMessage: String,
    onEnabledChange: (Boolean) -> Unit,
    onShowAd: () -> Unit,
    onPrivacyOptions: () -> Unit
) {
    val due = remainingMillis <= 0L
    ScreenColumn(padding) {
        Text("Google Ads timer", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Timer-gated interstitials with a minimum cooldown and a safe display point.", color = Color.LightGray, fontSize = 13.sp)
        SectionCard("Ad schedule") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Enable timed ads", fontWeight = FontWeight.SemiBold)
                    Text(if (adsEnabled) "Timer is enabled" else "Timer is paused", fontSize = 12.sp, color = Color.LightGray)
                }
                Switch(checked = adsEnabled, onCheckedChange = onEnabledChange)
            }
            Divider(color = Color(0xFF33485E))
            Text("Ad refresh interval: 10 seconds", fontWeight = FontWeight.SemiBold)
            Text("The timer makes a test interstitial eligible every 10 seconds. Ads are not forced automatically; tap the button when ready.", fontSize = 12.sp, color = Color.LightGray)
            Text("For production, use a much longer interval and follow AdMob frequency and placement policies.", fontSize = 12.sp, color = Color(0xFFFFD27A))
        }
        SectionCard("Next eligible ad") {
            Text(
                if (!adsEnabled) "PAUSED" else if (due) "AD READY" else formatCountdown(remainingMillis),
                fontSize = 28.sp,
                color = if (due && adsEnabled) Green else Color.White,
                fontWeight = FontWeight.Bold
            )
            Text("VPN status: ${if (previewConnected) "CONNECTED (preview)" else "DISCONNECTED (preview)"}", fontSize = 12.sp, color = Color.LightGray)
            Text("An interstitial is refused while the VPN state is connected. The real app should call this at a natural transition after the Xray service reports disconnected.", fontSize = 12.sp, color = Color.LightGray)
            Button(onClick = onShowAd, enabled = adsEnabled && due && !previewConnected) {
                Text("Show eligible test ad")
            }
            if (actionMessage.isNotBlank()) Text(actionMessage, color = Green, fontSize = 12.sp)
            Text("Ad status: ${adsController.lastStatus}", fontSize = 11.sp, color = Color.LightGray)
        }
        OutlinedButton(onClick = onPrivacyOptions, modifier = Modifier.fillMaxWidth()) {
            Text("Privacy & consent options")
        }
        Text(
            "Development uses Google's test App ID and test interstitial unit. Replace both with your own AdMob IDs and configure your consent messages before release.",
            color = Color(0xFFFFD27A),
            fontSize = 12.sp
        )
    }
}

private fun formatCountdown(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainingSeconds = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remainingSeconds)
}
