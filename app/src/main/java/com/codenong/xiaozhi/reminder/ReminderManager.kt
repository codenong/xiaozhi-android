package com.codenong.xiaozhi.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ReminderItem(
    val id: Int,
    val message: String,
    val triggerAtMs: Long
)

class ReminderManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("xiaozhi_reminders", Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun getCurrentTimeJson(): String {
        val now = Date()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val weekFmt = SimpleDateFormat("EEEE", Locale.CHINA)
        return JSONObject().apply {
            put("current_time", fmt.format(now))
            put("weekday", weekFmt.format(now))
            put("timezone", TimeZone.getDefault().id)
            put("timestamp_ms", now.time)
        }.toString()
    }

    @Synchronized
    fun createReminder(
        message: String,
        delaySeconds: Long? = null,
        triggerAt: String? = null
    ): Result<ReminderItem> {
        val cleanMsg = message.trim()
        if (cleanMsg.isEmpty()) {
            return Result.failure(IllegalArgumentException("请提供提醒内容 message"))
        }

        val nowMs = System.currentTimeMillis()
        val targetMs = when {
            delaySeconds != null && delaySeconds > 0 -> nowMs + delaySeconds * 1000L
            !triggerAt.isNullOrBlank() -> parseTriggerTime(triggerAt.trim(), nowMs)
                ?: return Result.failure(IllegalArgumentException("无法解析时间格式：$triggerAt，请使用 yyyy-MM-dd HH:mm 或 HH:mm"))
            else -> return Result.failure(IllegalArgumentException("请提供 delay_seconds 或 trigger_at"))
        }

        if (targetMs <= nowMs + 1000L) {
            return Result.failure(IllegalArgumentException("提醒时间必须在当前时间之后"))
        }

        val items = loadAllMutable().filter { it.triggerAtMs > nowMs }.toMutableList()
        val nextId = ((items.maxOfOrNull { it.id } ?: 1000) + 1).coerceAtLeast(1001)
        val item = ReminderItem(id = nextId, message = cleanMsg, triggerAtMs = targetMs)
        items.add(item)
        saveAll(items)
        scheduleAlarm(item)
        return Result.success(item)
    }

    @Synchronized
    fun listReminders(): List<ReminderItem> {
        val nowMs = System.currentTimeMillis()
        val active = loadAllMutable().filter { it.triggerAtMs > nowMs }.sortedBy { it.triggerAtMs }
        saveAll(active)
        return active
    }

    @Synchronized
    fun cancelReminder(id: Int? = null, keyword: String? = null): List<ReminderItem> {
        val items = loadAllMutable()
        val removed = mutableListOf<ReminderItem>()
        val kept = mutableListOf<ReminderItem>()
        val kw = keyword?.trim().orEmpty()

        for (item in items) {
            val matchId = id != null && item.id == id
            val matchKw = kw.isNotEmpty() && item.message.contains(kw, ignoreCase = true)
            if (matchId || (id == null && matchKw)) {
                cancelAlarm(item)
                removed.add(item)
            } else {
                kept.add(item)
            }
        }
        saveAll(kept)
        return removed
    }

    @Synchronized
    fun markTriggered(id: Int) {
        val kept = loadAllMutable().filterNot { it.id == id }
        saveAll(kept)
    }

    @Synchronized
    fun rescheduleAll() {
        val nowMs = System.currentTimeMillis()
        val active = loadAllMutable().filter { it.triggerAtMs > nowMs }
        saveAll(active)
        active.forEach { scheduleAlarm(it) }
    }

    fun formatTriggerTime(triggerAtMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        return fmt.format(Date(triggerAtMs))
    }

    private fun scheduleAlarm(item: ReminderItem) {
        val pending = buildPendingIntent(item)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAtMs, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAtMs, pending)
        }
    }

    private fun cancelAlarm(item: ReminderItem) {
        alarmManager.cancel(buildPendingIntent(item))
    }

    private fun buildPendingIntent(item: ReminderItem): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_FIRED
            putExtra(EXTRA_REMINDER_ID, item.id)
            putExtra(EXTRA_REMINDER_MESSAGE, item.message)
        }
        return PendingIntent.getBroadcast(
            context,
            item.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun parseTriggerTime(raw: String, nowMs: Long): Long? {
        val fullFormats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm"
        )
        for (pattern in fullFormats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.CHINA).apply { isLenient = false }.parse(raw)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }

        // Support "HH:mm" or "HH:mm:ss": today if future, otherwise tomorrow
        val timePatterns = listOf("HH:mm:ss", "HH:mm")
        for (pattern in timePatterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.CHINA).apply { isLenient = false }.parse(raw)
            }.getOrNull()
            if (parsed != null) {
                val parsedCal = Calendar.getInstance().apply { time = parsed }
                val target = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.HOUR_OF_DAY, parsedCal.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, parsedCal.get(Calendar.MINUTE))
                    set(Calendar.SECOND, parsedCal.get(Calendar.SECOND))
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= nowMs + 1000L) {
                        add(Calendar.DAY_OF_MONTH, 1)
                    }
                }
                return target.timeInMillis
            }
        }
        return null
    }

    private fun loadAllMutable(): MutableList<ReminderItem> {
        val raw = prefs.getString(KEY_REMINDERS, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
        val list = mutableListOf<ReminderItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optInt("id", -1)
            val msg = obj.optString("message", "")
            val ts = obj.optLong("triggerAtMs", 0L)
            if (id > 0 && msg.isNotBlank() && ts > 0L) {
                list.add(ReminderItem(id = id, message = msg, triggerAtMs = ts))
            }
        }
        return list
    }

    private fun saveAll(items: List<ReminderItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("message", item.message)
                put("triggerAtMs", item.triggerAtMs)
            })
        }
        prefs.edit().putString(KEY_REMINDERS, arr.toString()).apply()
    }

    companion object {
        const val ACTION_ALARM_FIRED = "com.codenong.xiaozhi.ACTION_REMINDER_FIRED"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_REMINDER_MESSAGE = "reminder_message"
        private const val KEY_REMINDERS = "reminders_json"
    }
}
