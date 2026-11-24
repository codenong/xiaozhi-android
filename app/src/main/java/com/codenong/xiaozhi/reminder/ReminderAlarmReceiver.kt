package com.codenong.xiaozhi.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.codenong.xiaozhi.service.XiaozhiService

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getIntExtra(ReminderManager.EXTRA_REMINDER_ID, -1)
        val message = intent.getStringExtra(ReminderManager.EXTRA_REMINDER_MESSAGE).orEmpty()
        if (message.isBlank()) return

        val svcIntent = Intent(context, XiaozhiService::class.java).apply {
            action = XiaozhiService.ACTION_TRIGGER_REMINDER
            putExtra(ReminderManager.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderManager.EXTRA_REMINDER_MESSAGE, message)
        }
        ContextCompat.startForegroundService(context, svcIntent)
    }
}
