package com.example.meditimer.notifications

import android.media.AudioManager
import android.media.ToneGenerator

object SoundHelper {
    fun playMinuteTick() {
        play(ToneGenerator.TONE_PROP_BEEP, 220, 70)
    }

    fun playCountdownFinished() {
        play(ToneGenerator.TONE_PROP_ACK, 900, 100)
    }

    private fun play(toneType: Int, durationMs: Int, volume: Int) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, volume) }.getOrNull() ?: return
        try {
            tone.startTone(toneType, durationMs)
            Thread.sleep((durationMs + 120).toLong())
        } catch (_: Throwable) {
        } finally {
            runCatching { tone.release() }
        }
    }
}
