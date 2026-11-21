package com.shellz.a2dpvolume.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlin.concurrent.thread

/**
 * Streams inaudible audio through the app's own AudioTrack on the media stream.
 *
 * Two jobs:
 *  1. Keeps the A2DP link "busy" so speakers and soundbars (Vizio included) don't drop into
 *     auto-standby and disconnect when nothing is playing.
 *  2. Gives the app a stream it owns, routed to the A2DP sink, whose gain it can set with
 *     AudioTrack.setVolume (exposed as [gain]).
 *
 * The signal is digital silence plus a tiny dither so some sinks don't treat it as "no audio".
 */
class KeepAlivePlayer {

    private var track: AudioTrack? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    /** 0.0..1.0 gain applied to this app's own track. */
    var gain: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            track?.setGainCompat(field)
        }

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        val sampleRate = 44_100
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, sampleRate) // ~0.25 s stereo 16-bit

        val newTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM
            )
        }

        track = newTrack
        newTrack.setGainCompat(gain)
        newTrack.play()
        running = true

        worker = thread(name = "a2dp-keepalive", isDaemon = true) {
            val frame = ShortArray(2048)
            var toggle = false
            while (running) {
                // +/-1 LSB dither: about -96 dBFS, inaudible.
                for (i in frame.indices) {
                    frame[i] = if (toggle) 1 else -1
                    toggle = !toggle
                }
                val written = newTrack.write(frame, 0, frame.size)
                if (written < 0) break
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null
    }

    private fun AudioTrack.setGainCompat(g: Float) {
        setVolume(g) // API 21+, matches minSdk
    }
}
