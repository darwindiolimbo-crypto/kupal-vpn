package com.kupalvpn.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SshConfig(
    val mode: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val proxyHost: String,
    val proxyPort: Int,
    val sniHost: String,
    val udpGatewayPort: Int,
    val payload: String
) {
    fun validate() {
        require(host.isNotBlank()) { "SSH server host is required" }
        require(port in 1..65535) { "SSH server port must be 1-65535" }
        require(username.isNotBlank()) { "SSH username is required" }
        require(password.isNotBlank()) { "SSH password is required" }
        if (mode == MODE_HTTP_PAYLOAD_PROXY || mode == MODE_TLS_PAYLOAD_PROXY) {
            require(proxyHost.isNotBlank()) { "HTTP proxy host is required for this mode" }
            require(proxyPort in 1..65535) { "HTTP proxy port must be 1-65535" }
        }
        if (mode != MODE_DIRECT) require(payload.isNotBlank()) { "HTTP payload is required for this mode" }
        if (mode == MODE_TLS_PAYLOAD_DIRECT || mode == MODE_TLS_PAYLOAD_PROXY) require(sniHost.isNotBlank()) { "TLS SNI host is required" }
        require(udpGatewayPort in 0..65535) { "UDP gateway port must be 0-65535 (0 disables UDP relay)" }
    }

    companion object {
        const val MODE_DIRECT = "direct_tcp"
        const val MODE_HTTP_PAYLOAD_DIRECT = "http_payload_direct"
        const val MODE_HTTP_PAYLOAD_PROXY = "http_payload_proxy"
        const val MODE_TLS_PAYLOAD_DIRECT = "tls_payload_direct"
        const val MODE_TLS_PAYLOAD_PROXY = "tls_payload_proxy"
    }
}

/** Per-profile SSH transport settings. These settings are kept locally on this device. */
object SshConfigStore {
    private const val PREFS = "kupal_ssh_profiles"
    private const val KEY_ALIAS = "kupalvpn_ssh_password_aes"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(context: Context, field: String) = "${ProfileStore.activeProfileId(context)}_$field"

    private fun getOrCreateEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptPassword(password: String): String {
        if (password.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey())
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val stored = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, stored, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, stored, cipher.iv.size, encrypted.size)
        return Base64.encodeToString(stored, Base64.NO_WRAP)
    }

    private fun decryptPassword(encoded: String): String {
        if (encoded.isEmpty()) return ""
        val stored = Base64.decode(encoded, Base64.NO_WRAP)
        require(stored.size > IV_LENGTH) { "Stored SSH password is invalid" }
        val iv = stored.copyOfRange(0, IV_LENGTH)
        val encrypted = stored.copyOfRange(IV_LENGTH, stored.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateEncryptionKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun readPassword(context: Context): String {
        val p = prefs(context)
        val encrypted = p.getString(key(context, "password_enc"), null)
        if (encrypted != null) {
            return runCatching { decryptPassword(encrypted) }.getOrElse {
                throw IllegalStateException("Cannot decrypt the saved SSH password. Re-enter it in SSH settings.", it)
            }
        }

        // Migrate credentials saved by the earlier plaintext implementation.
        val legacy = p.getString(key(context, "password"), null).orEmpty()
        if (legacy.isEmpty()) return ""
        val migrated = encryptPassword(legacy)
        p.edit()
            .putString(key(context, "password_enc"), migrated)
            .remove(key(context, "password"))
            .apply()
        return legacy
    }

    fun read(context: Context): SshConfig {
        val p = prefs(context)
        return SshConfig(
            mode = p.getString(key(context, "mode"), SshConfig.MODE_DIRECT) ?: SshConfig.MODE_DIRECT,
            host = p.getString(key(context, "host"), "") ?: "",
            port = p.getString(key(context, "port"), "22")?.toIntOrNull() ?: 22,
            username = p.getString(key(context, "username"), "") ?: "",
            password = readPassword(context),
            proxyHost = p.getString(key(context, "proxy_host"), "") ?: "",
            proxyPort = p.getString(key(context, "proxy_port"), "8080")?.toIntOrNull() ?: 8080,
            sniHost = p.getString(key(context, "sni_host"), "") ?: "",
            udpGatewayPort = p.getString(key(context, "udp_gateway_port"), "7300")?.toIntOrNull() ?: 7300,
            payload = p.getString(key(context, "payload"), DEFAULT_PAYLOAD) ?: DEFAULT_PAYLOAD
        )
    }

    fun savePayload(context: Context, payload: String) {
        prefs(context).edit().putString(key(context, "payload"), payload).apply()
    }

    fun save(context: Context, config: SshConfig) {
        prefs(context).edit()
            .putString(key(context, "mode"), config.mode)
            .putString(key(context, "host"), config.host.trim())
            .putString(key(context, "port"), config.port.toString())
            .putString(key(context, "username"), config.username)
            .putString(key(context, "password_enc"), encryptPassword(config.password))
            .remove(key(context, "password"))
            .putString(key(context, "proxy_host"), config.proxyHost.trim())
            .putString(key(context, "proxy_port"), config.proxyPort.toString())
            .putString(key(context, "sni_host"), config.sniHost.trim())
            .putString(key(context, "udp_gateway_port"), config.udpGatewayPort.toString())
            .putString(key(context, "payload"), config.payload)
            .apply()
    }

    const val DEFAULT_PAYLOAD = "CONNECT [host_port] HTTP/1.1[crlf]Host: [host_port][crlf]Proxy-Connection: Keep-Alive[crlf][crlf]"
}
