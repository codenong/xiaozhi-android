package com.codenong.xiaozhi.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

data class ContactMatch(
    val name: String,
    val number: String
)

sealed class CallPrepareResult {
    data class ReadyToCall(val displayName: String?, val number: String) : CallPrepareResult()
    data class MultipleMatches(val matches: List<ContactMatch>) : CallPrepareResult()
    data class Error(val message: String) : CallPrepareResult()
}

class PhoneCallManager(
    private val context: Context,
    private val onCallStateActiveChanged: (Boolean) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @Volatile
    private var pendingSpeakerphone: Boolean = false

    @Volatile
    private var pendingCallTarget: CallPrepareResult.ReadyToCall? = null

    @Volatile
    var isInSystemCall: Boolean = false
        private set

    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var legacyPhoneListener: PhoneStateListener? = null

    fun startMonitoring() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (telephonyCallback != null) return
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleTelephonyState(state)
                }
            }
            telephonyCallback = cb
            runCatching { telephonyManager.registerTelephonyCallback(context.mainExecutor, cb) }
        } else {
            if (legacyPhoneListener != null) return
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleTelephonyState(state)
                }
            }
            legacyPhoneListener = listener
            @Suppress("DEPRECATION")
            runCatching { telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }
        }
    }

    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let { cb ->
                runCatching { telephonyManager.unregisterTelephonyCallback(cb) }
            }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            legacyPhoneListener?.let { l ->
                runCatching { telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE) }
            }
            legacyPhoneListener = null
        }
    }

    private fun handleTelephonyState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!isInSystemCall) {
                    isInSystemCall = true
                    onCallStateActiveChanged(true)
                }
                if (state == TelephonyManager.CALL_STATE_OFFHOOK && pendingSpeakerphone) {
                    pendingSpeakerphone = false
                    mainHandler.postDelayed({
                        if (!hasBluetoothAudioConnected()) {
                            forceSpeakerphoneOn()
                        }
                    }, 500L)
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                pendingSpeakerphone = false
                if (isInSystemCall) {
                    isInSystemCall = false
                    onCallStateActiveChanged(false)
                }
            }
        }
    }

    fun prepareCall(target: String): CallPrepareResult {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) {
            return CallPrepareResult.Error("请提供联系人姓名或电话号码")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return CallPrepareResult.Error("缺少拨打电话权限（CALL_PHONE），请在系统设置或重启应用后授权")
        }

        val cleanedNumber = normalizePhoneNumber(trimmed)
        if (isPhoneNumberInput(trimmed, cleanedNumber)) {
            val ready = CallPrepareResult.ReadyToCall(displayName = null, number = cleanedNumber)
            pendingCallTarget = ready
            return ready
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return CallPrepareResult.Error("缺少读取通讯录权限（READ_CONTACTS），无法按姓名查找联系人")
        }

        val matches = queryContacts(trimmed)
        return when {
            matches.isEmpty() -> CallPrepareResult.Error("通讯录中未找到与「$trimmed」匹配的联系人")
            matches.size == 1 -> {
                val m = matches.first()
                val ready = CallPrepareResult.ReadyToCall(displayName = m.name, number = m.number)
                pendingCallTarget = ready
                ready
            }
            else -> {
                val exact = matches.filter { it.name.equals(trimmed, ignoreCase = true) }
                if (exact.size == 1) {
                    val m = exact.first()
                    val ready = CallPrepareResult.ReadyToCall(displayName = m.name, number = m.number)
                    pendingCallTarget = ready
                    ready
                } else {
                    CallPrepareResult.MultipleMatches(matches.take(5))
                }
            }
        }
    }

    fun searchContacts(name: String): List<ContactMatch> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        return queryContacts(name.trim()).take(8)
    }

    fun hasPendingCall(): Boolean = pendingCallTarget != null

    @SuppressLint("MissingPermission")
    fun executePendingCall() {
        val target = pendingCallTarget ?: return
        pendingCallTarget = null

        val useBluetooth = hasBluetoothAudioConnected()
        pendingSpeakerphone = !useBluetooth

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:${Uri.encode(target.number)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!useBluetooth) {
                putExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            }
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure { err ->
            pendingSpeakerphone = false
            Log.e(TAG, "Failed to start ACTION_CALL: ${err.message}", err)
        }
    }

    fun cancelPendingCall() {
        pendingCallTarget = null
        pendingSpeakerphone = false
    }

    fun hasBluetoothAudioConnected(): Boolean {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
    }

    private fun forceSpeakerphoneOn() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }
        }
    }

    private fun queryContacts(keyword: String): List<ContactMatch> {
        if (keyword.isEmpty()) return emptyList()
        val results = mutableListOf<ContactMatch>()
        val seenNumbers = mutableSetOf<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$keyword%")

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)?.trim().orEmpty()
                    val rawNum = cursor.getString(numIdx)?.trim().orEmpty()
                    val norm = normalizePhoneNumber(rawNum)
                    if (name.isNotEmpty() && norm.isNotEmpty() && seenNumbers.add("$name|$norm")) {
                        results.add(ContactMatch(name = name, number = norm))
                    }
                }
            }
        }.onFailure { err ->
            Log.w(TAG, "Contact query failed: ${err.message}")
        }
        return results
    }

    private fun isPhoneNumberInput(raw: String, cleaned: String): Boolean {
        val hasLetterOrCjk = raw.any { it.isLetter() }
        return !hasLetterOrCjk && cleaned.count { it.isDigit() } >= 3
    }

    private fun normalizePhoneNumber(raw: String): String {
        return raw.filterIndexed { idx, ch ->
            ch.isDigit() || (ch == '+' && idx == 0)
        }
    }

    companion object {
        private const val TAG = "PhoneCallManager"
    }
}
