package com.kupalvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.lang.reflect.Proxy
import org.json.JSONObject

class KupalVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var coreClass: Class<*>? = null
    private var coreStarted = false
    private val vpnTimeHandler = Handler(Looper.getMainLooper())
    private val vpnTimeTick = object : Runnable {
        override fun run() {
            if (tun == null) return
            val remaining = LocalStore.remainingVpnMillis(this@KupalVpnService)
            if (remaining <= 0L) {
                updateNotification("VPN time expired")
                stopTunnel()
            } else {
                updateNotification("VPN active · ${formatTime(remaining)} remaining")
                vpnTimeHandler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START -> {
                if (tun != null) return START_NOT_STICKY
                try {
                    startForeground(NOTIFICATION_ID, notification("Preparing secure tunnel"))
                    val config = intent.getStringExtra(EXTRA_CONFIG)
                    if (LocalStore.remainingVpnMillis(this) <= 0L) {
                        setState("error: Watch a rewarded ad to get 2 hours of VPN time")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    } else if (config.isNullOrBlank()) {
                        setState("error: Missing Xray configuration")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    } else startTunnel(config)
                } catch (error: Exception) {
                    Log.e(TAG, "VPN start command failed", error)
                    setState("error: ${error.message ?: error.javaClass.simpleName}")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            else -> {
                if (tun == null) setState("disconnected")
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(configText: String) {
        if (tun != null) return
        setState("connecting")
        try {
            val config = JSONObject(configText)
            require(config.optJSONArray("outbounds")?.length()?.let { it > 0 } == true) {
                "Xray configuration must contain at least one outbound"
            }
            val mode = TunnelModeStore.readMode(this)
            require(mode in listOf(TunnelModeStore.MODE_XRAY, TunnelModeStore.MODE_HTTP, TunnelModeStore.MODE_SSH)) {
                "Unsupported tunnel mode: $mode"
            }
            if (mode == TunnelModeStore.MODE_SSH) error("SSH tunnel engine is unavailable; SSH mode cannot connect yet.")

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

            if (mode == TunnelModeStore.MODE_HTTP) TunnelModeStore.applyHttpProxy(this, config)
            val env = config.optJSONObject("env") ?: JSONObject().also { config.put("env", it) }
            env.put("xray.tun.fd", tun!!.fd.toString())
            val inbounds = config.optJSONArray("inbounds") ?: org.json.JSONArray().also { config.put("inbounds", it) }
            val tunInbound = JSONObject()
                .put("tag", "kupal-tun-in")
                .put("protocol", "tun")
                .put("settings", JSONObject().put("mtu", 1500))
            val existingTun = (0 until inbounds.length()).firstOrNull { inbounds.optJSONObject(it)?.optString("protocol") == "tun" }
            if (existingTun == null) inbounds.put(tunInbound) else inbounds.put(existingTun, tunInbound)

            registerSocketProtection()
            invokeCore("runXray", JSONObject().put("xrayJson", config.toString()))
            coreStarted = true
            if (!LocalStore.startVpnTime(this)) error("VPN time credit expired before connection")
            setState("connected")
            updateNotification("Kupal VPN connected · rewarded time active")
            vpnTimeHandler.removeCallbacks(vpnTimeTick)
            vpnTimeHandler.postDelayed(vpnTimeTick, 1_000L)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start Xray VPN", error)
            setState("error: ${error.message ?: error.javaClass.simpleName}")
            if (coreStarted) runCatching { invokeCore("stopXray") }.onFailure { Log.w(TAG, "Failed to stop Xray after startup error", it) }
            coreStarted = false
            runCatching { coreClass?.methods?.firstOrNull { it.name == "resetDNS" && it.parameterTypes.isEmpty() }?.invoke(null) }
            vpnTimeHandler.removeCallbacks(vpnTimeTick)
            LocalStore.pauseVpnTime(this)
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
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, called, args ->
            when (called.name) {
                "protectFd" -> {
                    val fd = (args?.firstOrNull() as? Number)?.toInt() ?: -1
                    fd >= 0 && protect(fd)
                }
                "toString" -> "KupalVpnSocketProtection"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> when (called.returnType) {
                    java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> false
                    java.lang.Integer.TYPE, java.lang.Integer::class.java -> 0
                    java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
                    java.lang.Void.TYPE -> null
                    else -> null
                }
            }
        }
        method.invoke(null, callback)
        val setDns = clazz.methods.firstOrNull { it.name == "setDNS" && it.parameterTypes.size == 2 }
            ?: error("libXray DNS API is missing")
        setDns.invoke(null, callback, "1.1.1.1:53")
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
        if (!response.optBoolean("success", false)) error(response.optString("error", "Xray core request failed"))
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
            if (coreStarted) invokeCore("stopXray")
        } catch (error: Exception) {
            Log.w(TAG, "Xray stop returned an error", error)
        } finally {
            coreStarted = false
            vpnTimeHandler.removeCallbacks(vpnTimeTick)
            LocalStore.pauseVpnTime(this)
            runCatching { coreClass?.methods?.firstOrNull { it.name == "resetDNS" && it.parameterTypes.isEmpty() }?.invoke(null) }
                .onFailure { Log.w(TAG, "Could not reset Xray DNS resolver", it) }
            runCatching { tun?.close() }.onFailure { Log.w(TAG, "Could not close VPN interface", it) }
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
        if (tun != null || coreStarted) stopTunnel()
        super.onDestroy()
    }

    private fun notification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VPN connection", NotificationManager.IMPORTANCE_LOW))
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("Kupal VPN")
            .setContentText(message)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(pending)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun setState(state: String) {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit().putString(STATE_KEY, state).apply()
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
        val hours = seconds / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        return String.format(java.util.Locale.US, "%02d:%02d", hours, minutes)
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
