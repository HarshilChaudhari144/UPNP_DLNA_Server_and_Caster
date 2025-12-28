package com.example.mysecondapp.dlna_lib.core.statemachine

import com.example.mysecondapp.dlna_lib.core.models.PlaybackState
import com.example.mysecondapp.dlna_lib.core.models.TransportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class PlaybackStateMachine {

    private val _state = MutableStateFlow(
        PlaybackState(
            transportState = TransportState.STOPPED,
            position = null,
            duration = null,
            speed = "1",
            volume = null,
            muted = false,
            mediaItem = null
        )
    )
    val state = _state.asStateFlow()

    fun updateTransportState(ts: TransportState) {
        _state.update { it.copy(transportState = ts) }
    }

    fun updatePosition(pos: kotlin.time.Duration) {
        _state.update { it.copy(position = pos) }
    }

    fun updateVolume(vol: Int) {
        _state.update { it.copy(volume = vol) }
    }

    // Add other state transitions as needed
}