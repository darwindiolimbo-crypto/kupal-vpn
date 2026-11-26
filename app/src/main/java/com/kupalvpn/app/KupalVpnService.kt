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
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class KupalVpnService : VpnService() {
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var coreClass: Class<*>? = null
    @Volatile private var sshTunnel: SshTunnelEngine? = null
    @Volatile private var coreStarted = false
    @Volatile private var stopRequested = false
    private val starting = AtomicBoolean(false)
    private val startExecutor = Executors.newSingleThreadExecutor()
    private val vpnTimeHandler = Handler(Looper.getMainLooper())
    private val vpnTimeTick = object : Runnable {
        override fun run() {
            if (tun == null) return
            val activeSsh = sshTunnel
            if (activeSsh != null && !activeSsh.isRunning()) {
                stopTunnel("SSH session or native tunnel stopped unexpectedly. Check the SSH host, credentials, and forwarding service.")
                return
            }
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
            ACTION_STOP -> {
                stopRequested = true
                stopTunnel()
            }
            ACTION_START -> {
                if (tun != null || !starting.compareAndSet(false, true)) return START_NOT_STICKY
                stopRequested = false
                try {
                    startForeground(NOTIFICATION_ID, notification("Preparing secure tunnel"))
                } catch (error: Throwable) {
                    Log.e(TAG, "Could not start foreground VPN service", error)
                    setState("error: ${error.message ?: error.javaClass.simpleName}")
                    ConnectionLogStore.add(this, "ERROR", error.message ?: "Foreground service startup failed")
                    starting.set(false)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                val mode = runCatching { TunnelModeStore.readMode(this) }.getOrDefault(TunnelModeStore.MODE_XRAY)
                val xrayProblem = if (mode == TunnelModeStore.MODE_SSH) null else
                    runCatching { XrayConfigValidator.validate(config) }.exceptionOrNull()?.message
                val problem = when {
                    LocalStore.remainingVpnMillis(this) <= 0L -> "Watch a rewarded ad to get 2 hours of VPN time"
                    mode !in listOf(TunnelModeStore.MODE_SSH, TunnelModeStore.MODE_XRAY) -> "Unsupported connection mode"
                    xrayProblem != null -> xrayProblem
                    else -> null
                }
                if (problem != null) {
                    setState("error: $problem")
                    ConnectionLogStore.add(this, "ERROR", problem)
                    starting.set(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                } else {
                    try {
                        startExecutor.execute { startTunnel(config, startId) }
                    } catch (error: Throwable) {
                        Log.e(TAG, "Could not schedule tunnel startup", error)
                        setState("error: ${error.message ?: "startup failed"}")
                        starting.set(false)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(configText: String, startId: Int) {
        if (tun != null) { starting.set(false); return }
        try {
            val mode = TunnelModeStore.readMode(this)
            val sshConfig = if (mode == TunnelModeStore.MODE_SSH) {
                SshConfigStore.read(this).also { it.validate() }
            } else null
            if (mode != TunnelModeStore.MODE_SSH) XrayConfigValidator.validate(configText)
            setState("connecting")
            ConnectionLogStore.add(this, "CONNECTING", "Starting VPN tunnel")
            // Resolve physical SSH/proxy endpoints while Android is still using the
            // normal mobile/Wi-Fi network. Once the VPN TUN routes 0.0.0.0/0, these
            // lookups can otherwise be sent into the tunnel before SOCKS is ready.
            val sshAddresses = sshConfig?.let { resolveHostAddresses(it.host, "SSH server") } ?: emptyList()
            val proxyAddresses = sshConfig?.takeIf {
                it.mode == SshConfig.MODE_HTTP_PAYLOAD_PROXY || it.mode == SshConfig.MODE_TLS_PAYLOAD_PROXY
            }?.let { resolveHostAddresses(it.proxyHost, "HTTP proxy") } ?: emptyList()

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
            check(!stopRequested) { "Connection cancelled" }

            if (mode == TunnelModeStore.MODE_SSH) {
                val config = checkNotNull(sshConfig) { "SSH configuration is missing" }
                val engine = SshTunnelEngine(this, config, sshAddresses, proxyAddresses)
                sshTunnel = engine
                check(engine.start(tun!!.fd)) { "SSH tunnel engine did not start" }
            } else {
                val config = JSONObject(configText)
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
            }
            check(!stopRequested) { "Connection cancelled" }
            if (!LocalStore.startVpnTime(this)) error("VPN time credit expired before connection")
            setState("connected")
            ConnectionLogStore.add(this, "CONNECTED", "VPN tunnel established")
            updateNotification("Kupal VPN connected · rewarded time active")
            vpnTimeHandler.removeCallbacks(vpnTimeTick)
            vpnTimeHandler.postDelayed(vpnTimeTick, 1_000L)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to start VPN tunnel", error)
            val failureMessage = error.message ?: error.javaClass.simpleName
            setState(if (stopRequested) "disconnected" else "error: $failureMessage")
            ConnectionLogStore.add(this, if (stopRequested) "DISCONNECTED" else "ERROR", failureMessage)
            runCatching { if (coreStarted) invokeCore("stopXray") }
            coreStarted = false
            runCatching { coreClass?.methods?.firstOrNull { it.name == "resetDNS" && it.parameterTypes.isEmpty() }?.invoke(null) }
            runCatching { sshTunnel?.stop() }
            sshTunnel = null
            vpnTimeHandler.removeCallbacks(vpnTimeTick)
            LocalStore.pauseVpnTime(this)
            tun?.close()
            tun = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        } finally {
            starting.set(false)
        }
    }

    private fun resolveHostAddresses(host: String, label: String): List<InetAddress> {
        val normalized = host.trim().removePrefix("[").removeSuffix("]")
        require(normalized.isNotBlank()) { "$label host is required" }
        val addresses = InetAddress.getAllByName(normalized).distinctBy { it.hostAddress }
        require(addresses.isNotEmpty()) { "Could not resolve $label host: $host" }
        return addresses
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

    private fun stopTunnel(reason: String? = null) {
        stopRequested = true
        try {
            if (coreStarted) invokeCore("stopXray")
        } catch (error: Exception) {
            Log.w(TAG, "Xray stop returned an error", error)
        } finally {
            coreStarted = false
            runCatching { sshTunnel?.stop() }
            sshTunnel = null
            vpnTimeHandler.removeCallbacks(vpnTimeTick)
            LocalStore.pauseVpnTime(this)
            try {
                runCatching { coreClass?.methods?.firstOrNull { it.name == "resetDNS" && it.parameterTypes.isEmpty() }?.invoke(null) }
                .onFailure { Log.w(TAG, "Could not reset Xray DNS resolver", it) }
            } catch (dnsError: Exception) {
                Log.w(TAG, "Could not reset Xray DNS resolver", dnsError)
            }
            tun?.close()
            tun = null
            setState(if (reason.isNullOrBlank()) "disconnected" else "error: $reason")
            ConnectionLogStore.add(this, if (reason.isNullOrBlank()) "DISCONNECTED" else "ERROR", reason ?: "VPN disconnected")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopRequested = true
        if (tun != null || coreStarted || sshTunnel != null) stopTunnel()
        startExecutor.shutdownNow()
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
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putString(STATE_KEY, state)
            .putLong(STATE_UPDATED_KEY, System.currentTimeMillis())
            .apply()
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
        const val STATE_UPDATED_KEY = "vpn_state_updated_at"
        private const val TAG = "KupalVpnService"
        private const val CHANNEL_ID = "kupal_vpn_connection"
        private const val NOTIFICATION_ID = 4101
    }
}
