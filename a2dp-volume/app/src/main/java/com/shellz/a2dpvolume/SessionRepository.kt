package com.shellz.a2dpvolume

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LinkState { IDLE, SEARCHING, CONNECTED, DISCONNECTED, BLUETOOTH_OFF, NO_PERMISSION }

data class SessionState(
    val serviceRunning: Boolean = false,
    val linkState: LinkState = LinkState.IDLE,
    val speakerName: String? = null,
    val speakerAddress: String? = null,
    val volume: Int = 0,
    val minVolume: Int = 0,
    val maxVolume: Int = 15,
    val routedToBluetooth: Boolean = false,
    val keepAliveEnabled: Boolean = true,
)

/**
 * Single source of truth shared by the foreground service (writer) and the UI (reader).
 * A process-wide object keeps things simple; the service and activity live in the same process.
 */
object SessionRepository {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun update(transform: (SessionState) -> SessionState) = _state.update(transform)
}
