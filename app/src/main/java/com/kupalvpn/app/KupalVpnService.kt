package com.kupalvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.lang.reflect.Proxy
import org.json.JSONObject

class KupalVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var coreClass: Class<*>? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    setState("error: Missing Xray configuration")
                    stopSelf()
                } else startTunnel(config)
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(configText: String) {
        if (tun != null) return
        setState("connecting")
        try {
            startForeground(NOTIFICATION_ID, notification("Starting secure tunnel"))
            val builder = Builder()
                .setSession("Kupal VPN")
                .setMtu(1500)
                .addAddress("10.77.0.1", 30)
                .addAddress("fd77:77::1", 126)
                .addDnsServer("1.1.1.1")
                .addDnsServer("2606:4700:4700::1111")
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            tun = builder.establish() ?: error("Android did not create the VPN interface")

            val config = JSONObject(configText)
            val env = config.optJSONObject("env") ?: JSONObject().also { config.put("env", it) }
            env.put("xray.tun.fd", tun!!.fd)
            val inbounds = config.optJSONArray("inbounds") ?: org.json.JSONArray().also { config.put("inbounds", it) }
            val tunInbound = JSONObject()
                .put("tag", "kupal-tun-in")
                .put("protocol", "tun")
                .put("settings", JSONObject().put("mtu", 1500))
            val existingTun = (0 until inbounds.length()).firstOrNull { inbounds.optJSONObject(it)?.optString("protocol") == "tun" }
            if (existingTun == null) inbounds.put(tunInbound) else inbounds.put(existingTun, tunInbound)

            registerSocketProtection()
            invokeCore("runXray", JSONObject().put("xrayJson", config.toString()))
            setState("connected")
            updateNotification("Kupal VPN is connected")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start Xray VPN", error)
            setState("error: ${error.message ?: error.javaClass.simpleName}")
            tun?.close()
            tun = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun registerSocketProtection() {
        val clazz = loadCoreClass()
        val method = clazz.methods.firstOrNull { it.name == "registerDialerController" && it.parameterTypes.size == 1 }
            ?: error("libXray socket-protection API is missing")
        val callbackType = method.parameterTypes[0]
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, called, args ->
            if (called.name == "protectFd") {
                val fd = (args?.firstOrNull() as? Number)?.toInt() ?: -1
                fd >= 0 && protect(fd)
            } else false
        }
        method.invoke(null, callback)
    }

    private fun invokeCore(methodName: String, payload: JSONObject = JSONObject()): JSONObject {
        val clazz = loadCoreClass()
        val request = JSONObject()
            .put("apiVersion", 3)
            .put("method", methodName)
            .put("payload", payload)
        val invoke = clazz.methods.firstOrNull { it.name == "invoke" && it.parameterTypes.size == 1 }
            ?: error("libXray invoke API is missing")
        val responseText = invoke.invoke(null, request.toString()) as? String
            ?: error("libXray returned an empty response")
        val response = JSONObject(responseText)
        if (!response.optBoolean("success", false)) {
            error(response.optString("error", "Xray core request failed"))
        }
        return response
    }

    private fun loadCoreClass(): Class<*> {
        coreClass?.let { return it }
        val clazz = Class.forName("libXray.LibXray")
        coreClass = clazz
        return clazz
    }

    private fun stopTunnel() {
        try {
            if (tun != null) {
                invokeCore("stopXray")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Xray stop returned an error", error)
        } finally {
            tun?.close()
            tun = null
            setState("disconnected")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (tun != null) stopTunnel()
        super.onDestroy()
    }

    private fun notification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("Kupal VPN")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun setState(state: String) {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit().putString(STATE_KEY, state).apply()
    }

    companion object {
        const val ACTION_START = "com.kupalvpn.app.action.START_VPN"
        const val ACTION_STOP = "com.kupalvpn.app.action.STOP_VPN"
        const val EXTRA_CONFIG = "xray_config"
        const val STATE_PREFS = "kupal_vpn_runtime"
        const val STATE_KEY = "vpn_state"
        private const val TAG = "KupalVpnService"
        private const val CHANNEL_ID = "kupal_vpn_connection"
        private const val NOTIFICATION_ID = 4101
    }
}
