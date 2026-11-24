package com.codenong.xiaozhi.mcp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject

interface MusicControlListener {
    fun onPlayMusicUrl(url: String, title: String?): String
    fun onStopMusic(): String
    fun onPauseMusic(): String
    fun onResumeMusic(): String
}

interface PhoneControlListener {
    fun onCallTarget(target: String): Pair<String, Boolean>
    fun onSearchContact(name: String): String
}

interface ReminderControlListener {
    fun onGetCurrentTime(): String
    fun onCreateReminder(message: String, delaySeconds: Long?, triggerAt: String?): Pair<String, Boolean>
    fun onListReminders(): String
    fun onCancelReminder(id: Int?, keyword: String?): String
}

class McpServer(
    private val context: Context,
    private val onBrightnessRequest: (Int) -> Unit,
    private val musicControl: MusicControlListener? = null,
    private val phoneControl: PhoneControlListener? = null,
    private val reminderControl: ReminderControlListener? = null
) {
    fun handleRequest(payload: JSONObject): JSONObject? {
        val id = payload.opt("id")
        val method = payload.optString("method")
        val params = payload.optJSONObject("params") ?: JSONObject()

        if (id == null || id == JSONObject.NULL) return null

        return when (method) {
            "initialize" -> JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject().apply { put("tools", JSONObject()) })
                    put("serverInfo", JSONObject().apply {
                        put("name", "xiaozhi-android")
                        put("version", "1.0.0")
                    })
                })
            }

            "server/discover" -> JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", JSONObject().apply {
                    put("resultType", "complete")
                    put("supportedVersions", JSONArray(listOf("2026-07-28", "2024-11-05")))
                    put("capabilities", JSONObject().apply { put("tools", JSONObject()) })
                    put("ttlMs", 60000)
                    put("cacheScope", "private")
                })
            }

            "tools/list" -> buildToolsListResponse(id, params)
            "tools/call" -> handleToolCall(id, params)
            else -> JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", JSONObject().apply {
                    put("code", -32601)
                    put("message", "Method not found: $method")
                })
            }
        }
    }

    private fun buildToolsListResponse(id: Any, params: JSONObject): JSONObject {
        val isModern = params.optJSONObject("_meta")
            ?.optString("io.modelcontextprotocol/protocolVersion") == "2026-07-28"

        val tools = JSONArray().apply {
            put(toolSchema(
                name = "self.get_device_status",
                description = "Get current speaker volume and battery percentage.",
                properties = JSONObject()
            ))
            put(toolSchema(
                name = "self.audio_speaker.set_volume",
                description = "Set the speaker volume (0-100).",
                properties = JSONObject().put("volume", JSONObject().apply {
                    put("type", "integer")
                    put("minimum", 0)
                    put("maximum", 100)
                }),
                required = listOf("volume")
            ))
            put(toolSchema(
                name = "self.screen.set_brightness",
                description = "Set screen brightness (0-100).",
                properties = JSONObject().put("brightness", JSONObject().apply {
                    put("type", "integer")
                    put("minimum", 0)
                    put("maximum", 100)
                }),
                required = listOf("brightness")
            ))
            put(toolSchema(
                name = "self.music.play_url",
                description = "Play an audio/music HTTP(S) stream URL directly on the Android device speaker.",
                properties = JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "Direct HTTP/HTTPS audio URL (e.g. mp3/m4a)")
                    })
                    put("title", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional song title and artist")
                    })
                },
                required = listOf("url")
            ))
            put(toolSchema(
                name = "self.music.stop",
                description = "Stop currently playing music on the device.",
                properties = JSONObject()
            ))
            put(toolSchema(
                name = "self.music.pause",
                description = "Pause currently playing music on the device.",
                properties = JSONObject()
            ))
            put(toolSchema(
                name = "self.music.resume",
                description = "Resume paused music on the device.",
                properties = JSONObject()
            ))
            put(toolSchema(
                name = "self.phone.call",
                description = "Place a phone call to a contact name or phone number directly on the Android device (auto-uses Bluetooth hands-free if connected, otherwise speakerphone).",
                properties = JSONObject().put("target", JSONObject().apply {
                    put("type", "string")
                    put("description", "Contact name (e.g. 张三) or phone number (e.g. 13800138000)")
                }),
                required = listOf("target")
            ))
            put(toolSchema(
                name = "self.phone.search_contact",
                description = "Search contacts by name to find matching phone numbers.",
                properties = JSONObject().put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Contact name keyword")
                }),
                required = listOf("name")
            ))
            put(toolSchema(
                name = "self.reminder.get_current_time",
                description = "Get the current local date, time, weekday, and timezone on the Android device. Always call this before scheduling relative/tomorrow/clock reminders if you need the current date/time.",
                properties = JSONObject()
            ))
            put(toolSchema(
                name = "self.reminder.create",
                description = "Schedule an alarm/timer reminder on the Android device. For 'N minutes later', pass delay_seconds. For clock times like '14:20' or 'tomorrow 9am', pass trigger_at in 'yyyy-MM-dd HH:mm' (recommended after get_current_time) or 'HH:mm'.",
                properties = JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("type", "string")
                        put("description", "What to remind the user to do")
                    })
                    put("delay_seconds", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Relative delay in seconds from now (e.g. 600 for 10 minutes)")
                    })
                    put("trigger_at", JSONObject().apply {
                        put("type", "string")
                        put("description", "Target local time in 'yyyy-MM-dd HH:mm' or 'HH:mm' format")
                    })
                },
                required = listOf("message")
            ))
            put(toolSchema(
                name = "self.reminder.list",
                description = "List all pending reminders scheduled on the device.",
                properties = JSONObject()
            ))
            put(toolSchema(
                name = "self.reminder.cancel",
                description = "Cancel pending reminder(s) by numeric id or keyword.",
                properties = JSONObject().apply {
                    put("id", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Optional reminder ID")
                    })
                    put("keyword", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional keyword matching reminder message")
                    })
                }
            ))
        }

        val result = JSONObject().apply {
            if (isModern) put("resultType", "complete")
            put("tools", tools)
        }
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
    }

    private fun handleToolCall(id: Any, params: JSONObject): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val isModern = params.optJSONObject("_meta") != null

        val (text, isError) = when (name) {
            "self.get_device_status" -> getDeviceStatusJson() to false
            "self.audio_speaker.set_volume" -> {
                val vol = args.optInt("volume", 50).coerceIn(0, 100)
                setMediaVolume(vol)
                "true" to false
            }
            "self.screen.set_brightness" -> {
                val brightness = args.optInt("brightness", 80).coerceIn(0, 100)
                onBrightnessRequest(brightness)
                "true" to false
            }
            "self.music.play_url" -> {
                val url = args.optString("url", "").trim()
                val title = args.optString("title", "").trim().ifEmpty { null }
                if (url.isEmpty()) {
                    "Missing url" to true
                } else {
                    (musicControl?.onPlayMusicUrl(url, title) ?: "Music player unavailable") to false
                }
            }
            "self.music.stop" -> {
                (musicControl?.onStopMusic() ?: "Music player unavailable") to false
            }
            "self.music.pause" -> {
                (musicControl?.onPauseMusic() ?: "Music player unavailable") to false
            }
            "self.music.resume" -> {
                (musicControl?.onResumeMusic() ?: "Music player unavailable") to false
            }
            "self.phone.call" -> {
                val target = args.optString("target", "").trim()
                if (target.isEmpty()) {
                    "Missing target" to true
                } else {
                    phoneControl?.onCallTarget(target) ?: ("Phone control unavailable" to true)
                }
            }
            "self.phone.search_contact" -> {
                val contactName = args.optString("name", "").trim()
                if (contactName.isEmpty()) {
                    "Missing name" to true
                } else {
                    (phoneControl?.onSearchContact(contactName) ?: "Phone control unavailable") to false
                }
            }
            "self.reminder.get_current_time" -> {
                (reminderControl?.onGetCurrentTime() ?: "Reminder unavailable") to false
            }
            "self.reminder.create" -> {
                val message = args.optString("message", "").trim()
                val delaySec = if (args.has("delay_seconds") && !args.isNull("delay_seconds")) {
                    args.optLong("delay_seconds", -1L).takeIf { it > 0 }
                } else {
                    null
                }
                val triggerAt = args.optString("trigger_at", "").trim().ifEmpty { null }
                reminderControl?.onCreateReminder(message, delaySec, triggerAt)
                    ?: ("Reminder unavailable" to true)
            }
            "self.reminder.list" -> {
                (reminderControl?.onListReminders() ?: "Reminder unavailable") to false
            }
            "self.reminder.cancel" -> {
                val remId = if (args.has("id") && !args.isNull("id")) {
                    args.optInt("id", -1).takeIf { it > 0 }
                } else {
                    null
                }
                val kw = args.optString("keyword", "").trim().ifEmpty { null }
                (reminderControl?.onCancelReminder(remId, kw) ?: "Reminder unavailable") to false
            }
            else -> "Unknown tool: $name" to true
        }

        val result = JSONObject().apply {
            if (isModern) put("resultType", "complete")
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            }))
            put("isError", isError)
        }
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
    }

    private fun getDeviceStatusJson(): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volPct = (curVol * 100) / maxVol

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        return JSONObject().apply {
            put("audio_speaker", JSONObject().put("volume", volPct))
            put("battery", JSONObject().put("level", batteryPct))
        }.toString()
    }

    private fun setMediaVolume(volumePercent: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (maxVol * volumePercent / 100f).toInt().coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    private fun toolSchema(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String> = emptyList()
    ): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            if (required.isNotEmpty()) {
                put("required", JSONArray(required))
            }
        })
    }
}
