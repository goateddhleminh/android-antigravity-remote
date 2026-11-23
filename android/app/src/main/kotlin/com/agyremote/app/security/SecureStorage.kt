package com.agyremote.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.agyremote.app.model.AppJson
import com.agyremote.app.model.PairedHost
import com.agyremote.app.model.QuickCommand

/**
 * Stores all tokens, paired hosts, and settings encrypted using Android Keystore (AES256-GCM).
 */
class SecureStorage(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            // Recover from corrupted Android Keystore entries (e.g. after backup/restore or OS upgrade)
            context.deleteSharedPreferences(PREFS_NAME)
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    fun loadHosts(): List<PairedHost> {
        val raw = prefs.getString(KEY_HOSTS, null) ?: return emptyList()
        return runCatching { AppJson.decodeFromString<List<PairedHost>>(raw) }.getOrDefault(emptyList())
    }

    fun saveHosts(hosts: List<PairedHost>) {
        prefs.edit().putString(KEY_HOSTS, AppJson.encodeToString(hosts)).apply()
    }

    fun upsertHost(host: PairedHost): List<PairedHost> {
        val current = loadHosts().filterNot { it.id == host.id }
        val updated = listOf(host) + current
        saveHosts(updated)
        return updated
    }

    fun removeHost(hostId: String): List<PairedHost> {
        val updated = loadHosts().filterNot { it.id == hostId }
        saveHosts(updated)
        return updated
    }

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC, false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    fun loadQuickCommands(): List<QuickCommand> {
        val raw = prefs.getString(KEY_QUICK_COMMANDS, null) ?: return QuickCommand.defaults()
        return runCatching { AppJson.decodeFromString<List<QuickCommand>>(raw) }
            .getOrDefault(QuickCommand.defaults())
    }

    fun saveQuickCommands(commands: List<QuickCommand>) {
        prefs.edit().putString(KEY_QUICK_COMMANDS, AppJson.encodeToString(commands)).apply()
    }

    fun loadRemoteDevices(): List<com.agyremote.app.model.RemoteDevice> {
        val raw = prefs.getString(KEY_REMOTE_DEVICES, null) ?: return emptyList()
        return runCatching { AppJson.decodeFromString<List<com.agyremote.app.model.RemoteDevice>>(raw) }
            .getOrDefault(emptyList())
    }

    fun upsertRemoteDevice(device: com.agyremote.app.model.RemoteDevice): List<com.agyremote.app.model.RemoteDevice> {
        val current = loadRemoteDevices().filterNot { it.id == device.id || it.url == device.url }
        val updated = listOf(device) + current
        prefs.edit().putString(KEY_REMOTE_DEVICES, AppJson.encodeToString(updated)).apply()
        return updated
    }

    fun removeRemoteDevice(id: String): List<com.agyremote.app.model.RemoteDevice> {
        val updated = loadRemoteDevices().filterNot { it.id == id }
        prefs.edit().putString(KEY_REMOTE_DEVICES, AppJson.encodeToString(updated)).apply()
        return updated
    }

    fun useCustomTabs(): Boolean = prefs.getBoolean(KEY_USE_CUSTOM_TABS, true)

    fun setUseCustomTabs(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_CUSTOM_TABS, enabled).apply()
    }

    private companion object {
        const val PREFS_NAME = "agy_remote_secure_prefs"
        const val KEY_HOSTS = "paired_hosts_v1"
        const val KEY_BIOMETRIC = "biometric_lock_v1"
        const val KEY_QUICK_COMMANDS = "quick_commands_v1"
        const val KEY_REMOTE_DEVICES = "remote_devices_v1"
        const val KEY_USE_CUSTOM_TABS = "use_custom_tabs_v1"
    }
}
