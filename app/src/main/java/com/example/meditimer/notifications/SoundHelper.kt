package com.example.meditimer.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.meditimer.R

object SoundHelper {
    fun playMinuteTick(context: Context) {
        playRaw(context, R.raw.countdown_tick)
    }

    fun playCountdownFinished(context: Context) {
        playRaw(context, R.raw.countdown_finish)
    }

    private fun playRaw(context: Context, resId: Int) {
        val afd = runCatching { context.resources.openRawResourceFd(resId) }.getOrNull() ?: return
        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                prepare()
            }
        }.getOrNull()
        runCatching { afd.close() }
        if (player == null) return

        try {
            player.start()
            Thread.sleep((player.duration.coerceAtLeast(150) + 100).toLong())
        } catch (_: Throwable) {
        } finally {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }
}
