package com.codenong.xiaozhi.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.os.BatteryManager
import android.speech.tts.TextToSpeech
import java.util.Locale

class BatteryAlertMonitor(
    private val context: Context
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingMessage: String? = null

    private var lastAlertedThreshold: Int = Int.MAX_VALUE
    private var receiverRegistered = false

    private val thresholds = listOf(20, 10, 5)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            handleBatteryIntent(intent)
        }
    }

    fun start() {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    val res = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.setLanguage(Locale.CHINESE)
                    }
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    // 语速放慢到 0.75 倍，播报更清晰
                    engine.setSpeechRate(0.75f)
                    ttsReady = true
                    pendingMessage?.let { msg ->
                        pendingMessage = null
                        speak(msg)
                    }
                }
            }
        }

        if (!receiverRegistered) {
            val sticky = context.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            receiverRegistered = true
            sticky?.let { handleBatteryIntent(it) }
        }
    }

    private fun handleBatteryIntent(intent: Intent) {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        if (isCharging) {
            lastAlertedThreshold = Int.MAX_VALUE
            return
        }

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return

        val percent = (level * 100) / scale
        val matched = thresholds.lastOrNull { percent <= it } ?: return

        if (matched < lastAlertedThreshold) {
            lastAlertedThreshold = matched
            val text = when (matched) {
                20 -> "电量只剩百分之二十了，请记得充电哦。"
                10 -> "电量只剩百分之十了，请尽快充电哦。"
                else -> "电量只剩百分之五了，马上要关机了，请快点充电。"
            }
            speak(text)
        }
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingMessage = text
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "battery_alert_${System.currentTimeMillis()}")
    }

    fun stop() {
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(batteryReceiver) }
            receiverRegistered = false
        }
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ttsReady = false
        pendingMessage = null
    }
}
