package com.kupalvpn.app

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun CrashDiagnosticsCard() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(runCatching {
        File(context.filesDir, KupalVpnApplication.CRASH_FILE).takeIf { it.isFile }?.readText()?.take(96 * 1024).orEmpty()
    }.getOrDefault("")) }
    if (report.isNotBlank()) {
        SectionCard("Previous crash report") {
            Text("The previous fatal exception was saved locally. Share it to identify the device-side crash.", color = Color.LightGray, fontSize = 12.sp)
            OutlinedButton(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Kupal VPN crash report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(intent, "Share crash report"))
            }, modifier = Modifier.fillMaxWidth()) { Text("Share crash report") }
            OutlinedButton(onClick = {
                File(context.filesDir, KupalVpnApplication.CRASH_FILE).delete()
                report = ""
            }, modifier = Modifier.fillMaxWidth()) { Text("Clear report") }
        }
    }
}