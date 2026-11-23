package com.kupalvpn.app

import android.net.VpnService
import android.util.Base64
import hev.htproxy.TProxyService
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * SSH transport: device TUN -> Hev tun2socks -> local SOCKS5 -> SSH direct-tcpip channels.
 * The physical SSH socket is protected from this app's VpnService to prevent routing loops.
 */
class SshTunnelEngine(
    private val vpnService: VpnService,
    private val config: SshConfig
) {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val worker = Executors.newCachedThreadPool()
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private var session: Session? = null
    private var socksServer: ServerSocket? = null
    private var hevConfigFile: File? = null

    fun start(tunFd: Int): Boolean {
        check(running.compareAndSet(false, true)) { "SSH tunnel is already running" }
        try {
            config.validate()
            val jsch = JSch()
            jsch.hostKeyRepository = SshHostKeyRepository(vpnService)
            val sshSession = jsch.getSession(config.username, config.host.trim(), config.port)
            sshSession.setPassword(config.password)
            sshSession.setConfig("StrictHostKeyChecking", "yes")
            sshSession.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            sshSession.setServerAliveInterval(15_000)
            sshSession.setServerAliveCountMax(3)
            sshSession.setSocketFactory(ProtectedSshSocketFactory(vpnService, config))
            sshSession.connect(25_000)
            session = sshSession

            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOCAL_SOCKS_PORT))
            socksServer = server
            worker.execute { acceptSocksClients(server, sshSession) }

            val nativeConfig = """
                tunnel:
                  name: kupal0
                  mtu: 1500
                  ipv4: 10.77.0.1
                  ipv6: 'fd77:77::1'
                  icmp: 'off'
                socks5:
                  address: 127.0.0.1
                  port: $LOCAL_SOCKS_PORT
                  udp: 'tcp'
            """.trimIndent()
            val configFile = File(vpnService.filesDir, "hev-ssh-tunnel.yml")
            configFile.writeText(nativeConfig)
            hevConfigFile = configFile
            check(TProxyService.TProxyStartService(configFile.absolutePath, tunFd)) {
                "Hev SOCKS tunnel could not start"
            }
            return true
        } catch (error: Exception) {
            stop()
            throw IllegalStateException("SSH tunnel start failed: ${error.message ?: error.javaClass.simpleName}", error)
        } catch (error: LinkageError) {
            stop()
            throw IllegalStateException("Hev tunnel library is missing or incompatible: ${error.message}", error)
        }
    }

    fun stop() {
        running.set(false)
        runCatching { TProxyService.TProxyStopService() }
        runCatching { socksServer?.close() }
        socksServer = null
        clientSockets.toList().forEach { runCatching { it.close() } }
        clientSockets.clear()
        runCatching { session?.disconnect() }
        session = null
        worker.shutdownNow()
        runCatching { worker.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { hevConfigFile?.delete() }
        hevConfigFile = null
    }

    fun isRunning(): Boolean =
        running.get() && (runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false))

    private fun acceptSocksClients(server: ServerSocket, sshSession: Session) {
        while (running.get() && !server.isClosed) {
            try {
                val client = server.accept()
                client.soTimeout = 15_000
                clientSockets.add(client)
                worker.execute { handleSocksClient(client, sshSession) }
            } catch (_: SocketTimeoutException) {
                // Re-check running state.
            } catch (_: Exception) {
                if (running.get()) Thread.sleep(100)
            }
        }
    }

    private fun handleSocksClient(client: Socket, sshSession: Session) {
        var channel: ChannelDirectTCPIP? = null
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            if (readByte(input) != 5) error("Unsupported SOCKS version")
            val methodCount = readByte(input)
            var noAuthAvailable = false
            repeat(methodCount) { if (readByte(input) == 0) noAuthAvailable = true }
            if (!noAuthAvailable) {
                output.write(byteArrayOf(5, 0xFF.toByte()))
                output.flush()
                return
            }
            output.write(byteArrayOf(5, 0))
            output.flush()

            if (readByte(input) != 5) error("Invalid SOCKS request")
            val command = readByte(input)
            readByte(input) // Reserved
            val addressType = readByte(input)
            if (command != 1) {
                writeSocksReply(output, 7)
                return
            }
            val destination = when (addressType) {
                1 -> InetAddress.getByAddress(readBytes(input, 4)).hostAddress
                3 -> String(readBytes(input, readByte(input)), Charsets.UTF_8)
                4 -> InetAddress.getByAddress(readBytes(input, 16)).hostAddress
                else -> {
                    writeSocksReply(output, 8)
                    return
                }
            }
            val destinationPort = (readByte(input) shl 8) or readByte(input)
            require(destinationPort in 1..65535) { "Invalid SOCKS destination port" }

            val sshChannel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel = sshChannel
            sshChannel.setHost(destination)
            sshChannel.setPort(destinationPort)
            sshChannel.setOrgIPAddress(client.inetAddress.hostAddress ?: "127.0.0.1")
            sshChannel.setOrgPort(client.port)
            sshChannel.setInputStream(input)
            sshChannel.setOutputStream(output)
            sshChannel.connect(20_000)

            val deadline = System.currentTimeMillis() + 20_000L
            while (running.get() && !sshChannel.isConnected && sshChannel.isConnected.not() &&
                System.currentTimeMillis() < deadline) {
                if (!sshSession.isConnected) error("SSH session disconnected")
                Thread.sleep(25)
            }
            if (!sshChannel.isConnected) {
                writeSocksReply(output, 5)
                error("SSH server refused the requested TCP destination")
            }
            client.soTimeout = 0
            writeSocksReply(output, 0)
            while (running.get() && client.isConnected && !client.isClosed &&
                sshChannel.isConnected && !sshChannel.isClosed) {
                Thread.sleep(300)
            }
        } catch (_: Exception) {
            // A failed SOCKS client is isolated; the VPN service remains active.
        } finally {
            runCatching { channel?.disconnect() }
            clientSockets.remove(client)
            runCatching { client.close() }
        }
    }

    private class ProtectedSshSocketFactory(
        private val service: VpnService,
        private val config: SshConfig
    ) : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            val useHttpProxy = config.mode == SshConfig.MODE_HTTP_PAYLOAD_PROXY ||
                config.mode == SshConfig.MODE_TLS_PAYLOAD_PROXY
            val connectHost = if (useHttpProxy) config.proxyHost.trim() else host
            val connectPort = if (useHttpProxy) config.proxyPort else port
            val raw = Socket()
            try {
                check(service.protect(raw)) { "Android failed to protect the SSH socket" }
                raw.connect(InetSocketAddress(connectHost, connectPort), 20_000)
                raw.soTimeout = 15_000
                var active: Socket = raw
                if (config.mode == SshConfig.MODE_TLS_PAYLOAD_PROXY) {
                    val sni = config.sniHost.trim().ifBlank { connectHost }
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, sni, connectPort, true) as SSLSocket
                    val parameters = ssl.sslParameters
                    parameters.serverNames = listOf(SNIHostName(sni))
                    ssl.sslParameters = parameters
                    ssl.startHandshake()
                    ssl.soTimeout = 15_000
                    active = ssl
                }
                if (config.mode != SshConfig.MODE_DIRECT) {
                    val payload = renderPayload(config.payload, host, port)
                    active.getOutputStream().apply {
                        write(payload.toByteArray(Charsets.ISO_8859_1))
                        flush()
                    }
                    val response = readHttpResponseHeader(active.getInputStream())
                    val status = response.lineSequence().firstOrNull().orEmpty()
                    val statusCode = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                    check(statusCode in 200..299) { "Payload proxy rejected the tunnel: $status" }
                }
                return active
            } catch (error: Exception) {
                runCatching { raw.close() }
                throw error
            }
        }

        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()

        private fun renderPayload(template: String, host: String, port: Int): String {
            val hostPort = if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
            return template
                .replace("[host_port]", hostPort)
                .replace("[host]", host)
                .replace("[port]", port.toString())
                .replace("[crlf]", "\r\n")
                .replace("\\r\\n", "\r\n")
                .replace("\\n", "\r\n")
        }

        private fun readHttpResponseHeader(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            var matched = 0
            val marker = byteArrayOf(13, 10, 13, 10)
            while (bytes.size() < 32 * 1024) {
                val value = input.read()
                if (value < 0) error("Proxy closed before the HTTP response header")
                bytes.write(value)
                if (value.toByte() == marker[matched]) matched++ else matched = if (value == 13) 1 else 0
                if (matched == marker.size) return bytes.toByteArray().toString(Charsets.ISO_8859_1)
            }
            error("HTTP response header exceeded 32 KB")
        }
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) error("Unexpected end of SOCKS handshake")
        return value and 0xFF
    }

    private fun readBytes(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            if (count < 0) error("Unexpected end of SOCKS request")
            offset += count
        }
        return bytes
    }

    private fun writeSocksReply(output: OutputStream, status: Int) {
        output.write(byteArrayOf(5, status.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    companion object {
        private const val LOCAL_SOCKS_PORT = 10808
    }
}
