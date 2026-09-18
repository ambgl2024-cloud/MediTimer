package com.example.meditimer.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import com.example.meditimer.R

object SoundHelper {
    fun playMinuteTick(context: Context) {
        playRaw(context, R.raw.countdown_tick)
    }

    fun playCountdownFinished(context: Context) {
        playRaw(context, R.raw.countdown_finish)
    }

    private fun playRaw(context: Context, resId: Int) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val audioManager = context.getSystemService(AudioManager::class.java)
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching { audioManager.requestAudioFocus(focusRequest) }

        val afd = runCatching { context.resources.openRawResourceFd(resId) }.getOrNull()
        if (afd == null) {
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
            return
        }

        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                setVolume(1f, 1f)
                prepare()
            }
        }.getOrNull()
        runCatching { afd.close() }

        if (player == null) {
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
            return
        }

        try {
            player.start()
            Thread.sleep((player.duration.coerceAtLeast(150) + 120).toLong())
        } catch (_: Throwable) {
        } finally {
            runCatching { player.stop() }
            runCatching { player.release() }
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        }
    }
}
