package com.mark.infiniterecorder

import com.mark.infiniterecorder.model.RecorderState

object PocketProtectionPolicy {
    const val HOLD_DURATION_MS = 2_000L

    fun protectsPause(enabled: Boolean, state: RecorderState): Boolean =
        enabled && state in ACTIVE_STATES

    fun protectsStop(enabled: Boolean, state: RecorderState): Boolean =
        enabled && (state in ACTIVE_STATES || state == RecorderState.PAUSED)

    private val ACTIVE_STATES = setOf(
        RecorderState.PREPARING,
        RecorderState.LISTENING,
        RecorderState.RECORDING_SOUND,
        RecorderState.SILENCE_SUPPRESSED,
    )
}
