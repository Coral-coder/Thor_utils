package com.shellz.a2dpvolume.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import com.shellz.a2dpvolume.SessionRepository
import com.shellz.a2dpvolume.SessionState
import com.shellz.a2dpvolume.service.A2dpVolumeService
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<SessionState> = SessionRepository.state

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        // Seed the slider before the service has run.
        refreshVolume()
    }

    fun refreshVolume() {
        val s = AudioManager.STREAM_MUSIC
        val min = if (android.os.Build.VERSION.SDK_INT >= 28) audioManager.getStreamMinVolume(s) else 0
        SessionRepository.update {
            it.copy(
                volume = audioManager.getStreamVolume(s),
                minVolume = min,
                maxVolume = audioManager.getStreamMaxVolume(s),
            )
        }
    }

    fun start() = A2dpVolumeService.start(getApplication())

    fun stop() = A2dpVolumeService.stop(getApplication())

    fun setVolume(value: Int) {
        if (state.value.serviceRunning) {
            // Let the service record it so it can restore it after a reconnect.
            A2dpVolumeService.setVolume(getApplication(), value)
        } else {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
            refreshVolume()
        }
    }

    fun setKeepAlive(enabled: Boolean) {
        if (state.value.serviceRunning) {
            A2dpVolumeService.setKeepAlive(getApplication(), enabled)
        } else {
            SessionRepository.update { it.copy(keepAliveEnabled = enabled) }
        }
    }
}
