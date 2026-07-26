package com.mark.infiniterecorder

import com.mark.infiniterecorder.model.RecorderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketProtectionPolicyTest {
    @Test
    fun protectedHoldLastsTwoSeconds() {
        assertEquals(2_000L, PocketProtectionPolicy.HOLD_DURATION_MS)
    }

    @Test
    fun pauseIsProtectedOnlyDuringActiveCapture() {
        assertTrue(PocketProtectionPolicy.protectsPause(true, RecorderState.LISTENING))
        assertTrue(PocketProtectionPolicy.protectsPause(true, RecorderState.RECORDING_SOUND))
        assertFalse(PocketProtectionPolicy.protectsPause(true, RecorderState.PAUSED))
        assertFalse(PocketProtectionPolicy.protectsPause(true, RecorderState.IDLE))
    }

    @Test
    fun stopRemainsProtectedWhilePaused() {
        assertTrue(PocketProtectionPolicy.protectsStop(true, RecorderState.LISTENING))
        assertTrue(PocketProtectionPolicy.protectsStop(true, RecorderState.PAUSED))
        assertFalse(PocketProtectionPolicy.protectsStop(true, RecorderState.IDLE))
    }

    @Test
    fun disabledProtectionAllowsImmediateControls() {
        RecorderState.entries.forEach { state ->
            assertFalse(PocketProtectionPolicy.protectsPause(false, state))
            assertFalse(PocketProtectionPolicy.protectsStop(false, state))
        }
    }
}
