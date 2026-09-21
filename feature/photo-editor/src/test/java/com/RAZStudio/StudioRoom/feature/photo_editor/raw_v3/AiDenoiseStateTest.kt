package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.AiDenoiseEditSession
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.AiDenoiseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDenoiseStateTest {
    @Test
    fun `cancel restores committed state and apply commits draft`() {
        val initial = AiDenoiseState(enabled = true, strength = 40f)
        val changed = initial.copy(strength = 80f, detail = 60f)
        val session = AiDenoiseEditSession(committed = initial, draft = initial)
            .update(changed)

        assertEquals(initial, session.cancel().draft)
        assertEquals(changed, session.apply().committed)
    }

    @Test
    fun `reset clears draft without changing committed state`() {
        val committed = AiDenoiseState(enabled = true, strength = 40f)
        val session = AiDenoiseEditSession(committed = committed, draft = committed)
            .update(committed.copy(strength = 90f))
            .reset()

        assertEquals(committed, session.committed)
        assertEquals(AiDenoiseState.Default, session.draft)
    }

    @Test
    fun `applied state is active when a committed denoise run is enabled`() {
        val session = AiDenoiseEditSession(committed = AiDenoiseState(enabled = true), draft = AiDenoiseState(enabled = true))
        assertTrue(session.isApplied)
    }

    @Test
    fun `cache identity changes with denoise state and input-space version`() {
        val base = AiDenoiseCacheKey("source", AiDenoiseState.Default)
        assertNotEquals(base.stableId(), base.copy(state = AiDenoiseState(enabled = true)).stableId())
        assertNotEquals(base.stableId(), base.copy(inputSpaceVersion = 2).stableId())
        assertEquals(base.stableId(), base.stableId())
    }
}
