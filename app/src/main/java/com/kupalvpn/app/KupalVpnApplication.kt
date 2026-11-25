package com.kupalvpn.app

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KupalVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                File(filesDir, CRASH_FILE).writeText(
                    "Kupal VPN crash report\nTime: $stamp\nThread: ${thread.name}\n\n" +
                        error.stackTraceToString().take(96 * 1024)
                )
            }
            prior?.uncaughtException(thread, error)
        }
    }
    companion object { const val CRASH_FILE = "kupal-last-crash.txt" }
}