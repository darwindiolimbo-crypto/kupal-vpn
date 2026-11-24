package com.kupalvpn.app

import android.content.Context
import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo

/**
 * Trust-on-first-use host-key store. A first key is pinned locally; a changed key is rejected.
 * This prevents silently accepting a replacement key after the first connection.
 */
class SshHostKeyRepository(context: Context) : HostKeyRepository {
    private val preferences = context.applicationContext.getSharedPreferences("kupal_ssh_known_hosts", Context.MODE_PRIVATE)

    private fun preferenceKey(host: String): String =
        "host_" + Base64.encodeToString(host.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

    override fun check(host: String, key: ByteArray): Int {
        val name = preferenceKey(host)
        val incoming = Base64.encodeToString(key, Base64.NO_WRAP)
        val existing = preferences.getString(name, null)
        if (existing == null) {
            preferences.edit().putString(name, incoming).apply()
            return HostKeyRepository.OK
        }
        return if (existing == incoming) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        preferences.edit().putString(preferenceKey(hostkey.host), hostkey.key).apply()
    }

    override fun remove(host: String?, type: String?) {
        if (host == null) {
            preferences.edit().clear().apply()
            return
        }
        val key = preferenceKey(host)
        if (type == null) {
            preferences.edit().remove(key).apply()
            return
        }
        val stored = preferences.getString(key, null) ?: return
        val raw = runCatching { Base64.decode(stored, Base64.DEFAULT) }.getOrNull() ?: return
        val saved = runCatching { HostKey(host, raw) }.getOrNull() ?: return
        if (saved.type == type) preferences.edit().remove(key).apply()
    }

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        if (host == null) {
            preferences.edit().clear().apply()
            return
        }
        val prefKey = preferenceKey(host)
        val stored = preferences.getString(prefKey, null) ?: return
        if (key == null) {
            remove(host, type)
            return
        }
        val current = runCatching { Base64.decode(stored, Base64.DEFAULT) }.getOrNull() ?: return
        if (current.contentEquals(key)) preferences.edit().remove(prefKey).apply()
    }

    override fun getKnownHostsRepositoryID(): String = "kupal-ssh-tofu"

    override fun getHostKey(): Array<HostKey> =
        preferences.all.mapNotNull { (name, value) ->
            if (!name.startsWith("host_") || value !is String) return@mapNotNull null
            val host = runCatching {
                val encoded = name.removePrefix("host_")
                String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull() ?: return@mapNotNull null
            val raw = runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull() ?: return@mapNotNull null
            runCatching { HostKey(host, raw) }.getOrNull()
        }.toTypedArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        getHostKey().filter { (host == null || it.host == host) && (type == null || it.type == type) }.toTypedArray()
}
