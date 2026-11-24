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

class McpServer(
    private val context: Context,
    private val onBrightnessRequest: (Int) -> Unit,
    private val musicControl: MusicControlListener? = null
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
