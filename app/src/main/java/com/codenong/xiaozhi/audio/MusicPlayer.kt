package com.codenong.xiaozhi.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import android.util.Log

data class MusicPlaybackState(
    val title: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false
)

class MusicPlayer(
    private val context: Context,
    private val onStateChanged: (MusicPlaybackState) -> Unit
) {
    private var mediaPlayer: MediaPlayer? = null
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private var lastPositionMs: Int = 0
    private var isPrepared: Boolean = false
    private var isBuffering: Boolean = false
    private var deferredStart: Boolean = false
    private var pausedByUserOrChat: Boolean = false

    @Synchronized
    fun play(url: String, title: String?, deferStart: Boolean, seekToMs: Int = 0) {
        stopInternal(notify = false)
        currentUrl = url
        currentTitle = title?.takeIf { it.isNotBlank() } ?: "在线音乐"
        lastPositionMs = seekToMs
        isPrepared = false
        isBuffering = true
        deferredStart = deferStart
        pausedByUserOrChat = false
        notifyState()

        val mp = MediaPlayer()
        mediaPlayer = mp
        mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setOnPreparedListener { player ->
            synchronized(this) {
                if (mediaPlayer !== player) return@setOnPreparedListener
                isPrepared = true
                isBuffering = false
                if (lastPositionMs > 0) {
                    runCatching { player.seekTo(lastPositionMs) }
                    lastPositionMs = 0
                }
                if (!deferredStart && !pausedByUserOrChat) {
                    runCatching { player.start() }
                        .onFailure { Log.e(TAG, "MediaPlayer start failed: ${it.message}", it) }
                }
                notifyState()
            }
        }
        mp.setOnCompletionListener { player ->
            synchronized(this) {
                if (mediaPlayer !== player) return@setOnCompletionListener
                stopInternal(notify = true)
            }
        }
        mp.setOnErrorListener { player, what, extra ->
            Log.w(TAG, "MediaPlayer error: what=$what extra=$extra")
            synchronized(this) {
                if (mediaPlayer === player) {
                    stopInternal(notify = true)
                }
            }
            true
        }
        runCatching {
            mp.setDataSource(url)
            mp.prepareAsync()
        }.onFailure { err ->
            Log.e(TAG, "Failed to prepare audio URL: ${err.message}", err)
            stopInternal(notify = true)
        }
    }

    @Synchronized
    fun hasDeferredStart(): Boolean = deferredStart && mediaPlayer != null

    @Synchronized
    fun startDeferred() {
        if (!deferredStart) return
        deferredStart = false
        val mp = mediaPlayer ?: return
        if (isPrepared && !pausedByUserOrChat) {
            runCatching { mp.start() }
                .onFailure { Log.e(TAG, "startDeferred failed: ${it.message}", it) }
            notifyState()
        }
    }

    @Synchronized
    fun pause(): Boolean {
        val mp = mediaPlayer ?: return false
        pausedByUserOrChat = true
        deferredStart = false
        if (isPrepared && mp.isPlaying) {
            lastPositionMs = runCatching { mp.currentPosition }.getOrDefault(0)
            runCatching { mp.pause() }
                .onFailure { Log.w(TAG, "pause failed: ${it.message}", it) }
            notifyState()
            return true
        }
        notifyState()
        return false
    }

    @Synchronized
    fun resume(): Boolean {
        val url = currentUrl
        val title = currentTitle
        val mp = mediaPlayer
        if (mp == null) {
            if (!url.isNullOrBlank()) {
                play(url, title, deferStart = false, seekToMs = lastPositionMs)
                return true
            }
            return false
        }
        pausedByUserOrChat = false
        deferredStart = false
        if (!isPrepared) {
            notifyState()
            return true
        }
        if (!mp.isPlaying) {
            val started = runCatching {
                mp.start()
                mp.isPlaying
            }.onFailure { err ->
                Log.w(TAG, "resume start failed, will re-prepare: ${err.message}", err)
            }.getOrDefault(false)

            if (!started && !url.isNullOrBlank()) {
                play(url, title, deferStart = false, seekToMs = lastPositionMs)
                return true
            }
            notifyState()
            return started
        }
        notifyState()
        return true
    }

    @Synchronized
    fun isActive(): Boolean {
        val mp = mediaPlayer ?: return false
        return isBuffering || deferredStart || (isPrepared && mp.isPlaying)
    }

    @Synchronized
    fun stop() {
        stopInternal(notify = true)
    }

    @Synchronized
    fun release() {
        stopInternal(notify = false)
    }

    private fun stopInternal(notify: Boolean) {
        val mp = mediaPlayer
        mediaPlayer = null
        currentUrl = null
        currentTitle = null
        lastPositionMs = 0
        isPrepared = false
        isBuffering = false
        deferredStart = false
        pausedByUserOrChat = false
        if (mp != null) {
            runCatching {
                if (mp.isPlaying) mp.stop()
            }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        if (notify) {
            notifyState()
        }
    }

    private fun notifyState() {
        val playing = isPrepared && (mediaPlayer?.isPlaying == true)
        onStateChanged(
            MusicPlaybackState(
                title = currentTitle,
                isPlaying = playing,
                isBuffering = isBuffering
            )
        )
    }

    companion object {
        private const val TAG = "MusicPlayer"
    }
}
