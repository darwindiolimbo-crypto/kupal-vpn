package com.kupalvpn.app

import android.net.VpnService
import android.util.Base64
import hev.htproxy.TProxyService
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
            jsch.setHostKeyRepository(SshHostKeyRepository(vpnService))
            if (config.privateKey.isNotBlank()) {
                val passphrase = config.privateKeyPassphrase.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
                jsch.addIdentity(
                    "kupalvpn-profile-key",
                    config.privateKey.toByteArray(Charsets.UTF_8),
                    null,
                    passphrase
                )
            }
            val sshSession = jsch.getSession(config.username, config.host.trim(), config.port)
            if (config.password.isNotBlank()) sshSession.setPassword(config.password)
            sshSession.setConfig("StrictHostKeyChecking", "yes")
            sshSession.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
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
                  udp: 'udp'
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
            if (command != 1 && command != 3) {
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

            if (command == 3) {
                if (config.udpGatewayPort == 0) {
                    writeSocksReply(output, 7)
                    return
                }
                runUdpAssociate(client, input, output, sshSession)
                return
            }
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
            while (running.get() && !sshChannel.isConnected && !sshChannel.isClosed &&
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

    /**
     * SOCKS5 UDP ASSOCIATE bridge. UDP packets are framed using the BadVPN UDPGW
     * wire format and carried through a separate SSH direct-tcpip channel to
     * localhost:<udpGatewayPort> on the SSH server.
     */
    private fun runUdpAssociate(
        controlSocket: Socket,
        controlInput: InputStream,
        controlOutput: OutputStream,
        sshSession: Session
    ) {
        val udpChannel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
        var udpSocket: DatagramSocket? = null
        val active = AtomicBoolean(true)
        try {
            udpChannel.setHost("127.0.0.1")
            udpChannel.setPort(config.udpGatewayPort)
            udpChannel.setOrgIPAddress(controlSocket.inetAddress.hostAddress ?: "127.0.0.1")
            udpChannel.setOrgPort(controlSocket.port)
            val gatewayInput = DataInputStream(udpChannel.inputStream)
            val gatewayOutput = DataOutputStream(udpChannel.outputStream)
            udpChannel.connect(20_000)
            awaitChannel(udpChannel, sshSession, 20_000)

            val relay = DatagramSocket(null)
            udpSocket = relay
            relay.reuseAddress = true
            relay.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            relay.soTimeout = 1_000
            val relayPort = relay.localPort
            controlOutput.write(byteArrayOf(
                5, 0, 0, 1, 127, 0, 0, 1,
                ((relayPort shr 8) and 0xFF).toByte(), (relayPort and 0xFF).toByte()
            ))
            controlOutput.flush()

            val lastClient = AtomicReference<InetSocketAddress?>(null)
            val nextConnectionId = AtomicInteger(1)
            val idsByDestination = ConcurrentHashMap<String, Int>()
            val destinationById = ConcurrentHashMap<Int, String>()
            val gatewayWriteLock = Any()

            worker.execute {
                val buffer = ByteArray(65_535)
                while (active.get() && running.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        relay.receive(packet)
                        if (packet.length < 4) continue
                        val parsed = parseSocksUdpPacket(packet.data, packet.length) ?: continue
                        lastClient.set(InetSocketAddress(packet.address, packet.port))
                        val key = Base64.encodeToString(parsed.address, Base64.NO_WRAP) + ":" + parsed.port
                        val existingId = idsByDestination[key]
                        val connectionId = existingId ?: allocateUdpConnectionId(nextConnectionId, destinationById).also {
                            idsByDestination[key] = it
                            destinationById[it] = key
                        }
                        var flags = 0
                        if (parsed.address.size == 16) flags = flags or UDPGW_FLAG_IPV6
                        if (parsed.port == 53) flags = flags or UDPGW_FLAG_DNS
                        synchronized(gatewayWriteLock) {
                            writeUdpGwMessage(gatewayOutput, flags, connectionId, parsed.address, parsed.port, parsed.payload)
                        }
                    } catch (_: SocketTimeoutException) {
                        // Poll the active flag.
                    } catch (_: Exception) {
                        active.set(false)
                    }
                }
            }

            worker.execute {
                try {
                    while (active.get() && running.get()) {
                        val frameLength = gatewayInput.readUnsignedByte() or (gatewayInput.readUnsignedByte() shl 8)
                        if (frameLength < 3) error("Invalid UDPGW frame length")
                        val frame = ByteArray(frameLength)
                        gatewayInput.readFully(frame)
                        val flags = frame[0].toInt() and 0xFF
                        if ((flags and UDPGW_FLAG_KEEPALIVE) != 0) continue
                        if (frameLength < 9) error("UDPGW data frame is too short")
                        val connectionId = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)
                        if (!destinationById.containsKey(connectionId)) continue
                        val ipv6 = (flags and UDPGW_FLAG_IPV6) != 0
                        val addressLength = if (ipv6) 16 else 4
                        val headerLength = 3 + addressLength + 2
                        if (frame.size < headerLength) continue
                        val address = frame.copyOfRange(3, 3 + addressLength)
                        val portOffset = 3 + addressLength
                        // BadVPN address ports are stored in network byte order.
                        val sourcePort = ((frame[portOffset].toInt() and 0xFF) shl 8) or
                            (frame[portOffset + 1].toInt() and 0xFF)
                        val payload = frame.copyOfRange(headerLength, frame.size)
                        val peer = lastClient.get() ?: continue
                        val response = buildSocksUdpPacket(address, sourcePort, payload, ipv6)
                        relay.send(DatagramPacket(response, response.size, peer))
                    }
                } catch (_: Exception) {
                    active.set(false)
                }
            }

            worker.execute {
                while (active.get() && running.get()) {
                    try {
                        Thread.sleep(15_000L)
                        if (active.get()) {
                            synchronized(gatewayWriteLock) {
                                writeUdpGwMessage(
                                    gatewayOutput,
                                    UDPGW_FLAG_KEEPALIVE,
                                    0,
                                    byteArrayOf(0, 0, 0, 0),
                                    0,
                                    ByteArray(0)
                                )
                            }
                        }
                    } catch (_: InterruptedException) {
                        return@execute
                    } catch (_: Exception) {
                        active.set(false)
                    }
                }
            }

            controlSocket.soTimeout = 1_000
            while (active.get() && running.get() && sshSession.isConnected && udpChannel.isConnected && !controlSocket.isClosed) {
                try {
                    if (controlInput.read() < 0) break
                    // The SOCKS5 TCP control channel is reserved for association lifetime.
                    break
                } catch (_: SocketTimeoutException) {
                    // The UDP relay is active; poll for client/SSH shutdown.
                }
            }
        } finally {
            active.set(false)
            runCatching { udpSocket?.close() }
            runCatching { udpChannel.disconnect() }
        }
    }

    private data class ParsedSocksUdpPacket(
        val address: ByteArray,
        val port: Int,
        val payload: ByteArray
    )

    private fun parseSocksUdpPacket(bytes: ByteArray, length: Int): ParsedSocksUdpPacket? {
        if (length < 10 || bytes[0].toInt() != 0 || bytes[1].toInt() != 0 || bytes[2].toInt() != 0) return null
        var offset = 4
        val address = when (bytes[3].toInt() and 0xFF) {
            1 -> {
                if (length < offset + 6) return null
                bytes.copyOfRange(offset, offset + 4).also { offset += 4 }
            }
            4 -> {
                if (length < offset + 18) return null
                bytes.copyOfRange(offset, offset + 16).also { offset += 16 }
            }
            // UDPGW requires numeric destination addresses; Hev normally supplies these for TUN packets.
            else -> return null
        }
        val port = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        offset += 2
        if (port !in 1..65535 || offset > length) return null
        return ParsedSocksUdpPacket(address, port, bytes.copyOfRange(offset, length))
    }

    private fun allocateUdpConnectionId(
        counter: AtomicInteger,
        activeIds: ConcurrentHashMap<Int, String>
    ): Int {
        repeat(65_535) {
            val candidate = counter.getAndUpdate { if (it >= 65_535) 1 else it + 1 }
            if (!activeIds.containsKey(candidate)) return candidate
        }
        error("No UDP gateway connection IDs available")
    }

    private fun writeUdpGwMessage(
        output: DataOutputStream,
        flags: Int,
        connectionId: Int,
        address: ByteArray,
        port: Int,
        payload: ByteArray
    ) {
        val keepalive = (flags and UDPGW_FLAG_KEEPALIVE) != 0
        val bodyLength = 3 + if (keepalive) 0 else address.size + 2 + payload.size
        require(bodyLength <= 65_535) { "UDP datagram is too large for UDPGW" }
        output.writeByte(bodyLength and 0xFF)
        output.writeByte((bodyLength shr 8) and 0xFF)
        output.writeByte(flags and 0xFF)
        output.writeByte(connectionId and 0xFF)
        output.writeByte((connectionId shr 8) and 0xFF)
        if (!keepalive) {
            output.write(address)
            // BadVPN's packed BAddr uses network byte order for address ports.
            output.writeByte((port shr 8) and 0xFF)
            output.writeByte(port and 0xFF)
            output.write(payload)
        }
        output.flush()
    }

    private fun buildSocksUdpPacket(address: ByteArray, port: Int, payload: ByteArray, ipv6: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0, 0, 0, if (ipv6) 4 else 1))
        output.write(address)
        output.write((port shr 8) and 0xFF)
        output.write(port and 0xFF)
        output.write(payload)
        return output.toByteArray()
    }

    private fun awaitChannel(channel: ChannelDirectTCPIP, sshSession: Session, timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (running.get() && !channel.isConnected && !channel.isClosed && System.currentTimeMillis() < deadline) {
            if (!sshSession.isConnected) error("SSH session disconnected")
            Thread.sleep(25L)
        }
        check(channel.isConnected) { "SSH server refused or timed out opening the forwarding channel" }
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
                if (config.mode == SshConfig.MODE_TLS_PAYLOAD_DIRECT || config.mode == SshConfig.MODE_TLS_PAYLOAD_PROXY) {
                    val sni = config.sniHost.trim().ifBlank { connectHost }
                    val tlsPort = if (config.mode == SshConfig.MODE_TLS_PAYLOAD_PROXY) 443 else connectPort
                    if (config.mode == SshConfig.MODE_TLS_PAYLOAD_PROXY) {
                        establishHttpConnect(raw, sni, tlsPort)
                    }
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, sni, tlsPort, true) as SSLSocket
                    val parameters = ssl.sslParameters
                    if (!sni.matches(Regex("^[0-9.]+$"))) {
                        parameters.serverNames = listOf(SNIHostName(sni))
                    }
                    parameters.endpointIdentificationAlgorithm = "HTTPS"
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

        private fun establishHttpConnect(socket: Socket, targetHost: String, targetPort: Int) {
            val hostPort = if (targetHost.contains(':') && !targetHost.startsWith("[")) {
                "[$targetHost]:$targetPort"
            } else {
                "$targetHost:$targetPort"
            }
            val request = "CONNECT $hostPort HTTP/1.1\r\nHost: $hostPort\r\nProxy-Connection: Keep-Alive\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val response = readHttpResponseHeader(socket.getInputStream())
            val status = response.lineSequence().firstOrNull().orEmpty()
            val statusCode = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            check(statusCode in 200..299) { "HTTP proxy could not establish TLS tunnel: $status" }
        }

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
        private const val UDPGW_FLAG_KEEPALIVE = 0x01
        private const val UDPGW_FLAG_REBIND = 0x02
        private const val UDPGW_FLAG_DNS = 0x04
        private const val UDPGW_FLAG_IPV6 = 0x08
    }
}
