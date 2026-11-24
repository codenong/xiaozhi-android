package com.codenong.xiaozhi.data

import android.content.Context
import com.codenong.xiaozhi.model.DeviceIdentity
import java.security.SecureRandom
import java.util.UUID

class DeviceIdentityStore(context: Context) {

    private val prefs = context.getSharedPreferences("xiaozhi_identity", Context.MODE_PRIVATE)

    fun getIdentity(): DeviceIdentity {
        val clientId = prefs.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_CLIENT_ID, it).apply()
        }
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: generateVirtualMac().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        val otaUrl = prefs.getString(KEY_OTA_URL, DEFAULT_OTA_URL) ?: DEFAULT_OTA_URL
        return DeviceIdentity(deviceId = deviceId, clientId = clientId, otaUrl = otaUrl)
    }

    fun updateOtaUrl(url: String) {
        val normalized = url.trim().ifEmpty { DEFAULT_OTA_URL }
        prefs.edit().putString(KEY_OTA_URL, normalized).apply()
    }

    fun getSelectedWakeWord(): String? = prefs.getString(KEY_WAKE_WORD, null)

    fun setSelectedWakeWord(wakeWord: String?) {
        if (wakeWord.isNullOrBlank()) {
            prefs.edit().remove(KEY_WAKE_WORD).apply()
        } else {
            prefs.edit().putString(KEY_WAKE_WORD, wakeWord).apply()
        }
    }

    fun getUpdateCheckUrl(): String = prefs.getString(KEY_UPDATE_URL, "") ?: ""

    fun setUpdateCheckUrl(url: String) {
        prefs.edit().putString(KEY_UPDATE_URL, url.trim()).apply()
    }

    fun getGuideUrl(): String = prefs.getString(KEY_GUIDE_URL, "") ?: ""

    fun setGuideUrl(url: String) {
        prefs.edit().putString(KEY_GUIDE_URL, url.trim()).apply()
    }

    fun hasSeenGuide(): Boolean = prefs.getBoolean(KEY_GUIDE_SEEN, false)

    fun setGuideSeen(seen: Boolean = true) {
        prefs.edit().putBoolean(KEY_GUIDE_SEEN, seen).apply()
    }

    fun isPowerSaveEnabled(): Boolean = prefs.getBoolean(KEY_POWER_SAVE_ENABLED, true)

    fun setPowerSaveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_POWER_SAVE_ENABLED, enabled).apply()
    }

    private fun generateVirtualMac(): String {
        val random = SecureRandom()
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        // Locally administered unicast MAC: bit 1 = 1, bit 0 = 0
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
        return bytes.joinToString(":") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_OTA_URL = "ota_url"
        private const val KEY_WAKE_WORD = "selected_wake_word"
        private const val KEY_UPDATE_URL = "update_check_url"
        private const val KEY_GUIDE_URL = "guide_url"
        private const val KEY_GUIDE_SEEN = "guide_seen"
        private const val KEY_POWER_SAVE_ENABLED = "power_save_enabled"
    }
}
