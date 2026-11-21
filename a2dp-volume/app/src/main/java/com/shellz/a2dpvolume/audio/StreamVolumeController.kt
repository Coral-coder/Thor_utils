package com.shellz.a2dpvolume.audio

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Wraps AudioManager for STREAM_MUSIC, which is the stream Android sends to the A2DP sink.
 * On most phones with "absolute volume" (AVRCP) the speaker's own volume follows this value.
 */
class StreamVolumeController(
    context: Context,
    private val onVolumeChanged: (current: Int, min: Int, max: Int) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val resolver = context.contentResolver
    private val stream = AudioManager.STREAM_MUSIC

    val max: Int get() = audioManager.getStreamMaxVolume(stream)
    val min: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioManager.getStreamMinVolume(stream) else 0
    val current: Int get() = audioManager.getStreamVolume(stream)

    // Fires when the volume changes from anywhere: hardware keys, the speaker remote via AVRCP, etc.
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = publish()
    }

    fun start() {
        resolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        publish()
    }

    fun stop() {
        resolver.unregisterContentObserver(observer)
    }

    fun set(value: Int, showSystemUi: Boolean = false) {
        val clamped = value.coerceIn(min, max)
        val flags = if (showSystemUi) AudioManager.FLAG_SHOW_UI else 0
        audioManager.setStreamVolume(stream, clamped, flags)
        publish()
    }

    fun step(up: Boolean) {
        audioManager.adjustStreamVolume(
            stream,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0
        )
        publish()
    }

    /** True when media audio is currently going out over Bluetooth A2DP. */
    @Suppress("DEPRECATION")
    fun isRoutedToA2dp(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        }
        return audioManager.isBluetoothA2dpOn
    }

    private fun publish() = onVolumeChanged(current, min, max)
}
