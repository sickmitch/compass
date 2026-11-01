package org.compass.cng.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCompletionsTest {
    @Test fun duplicateOrOldCallbacksCannotFinishAnotherUtterance() {
        val queue = SpeechCompletions()
        var finished = 0
        queue.add("first") { finished++ }
        queue.add("second") { finished++ }
        queue.complete("first")
        queue.complete("first")
        assertEquals(1, finished)
        assertFalse(queue.isEmpty)
        queue.complete("second")
        assertEquals(2, finished)
        assertTrue(queue.isEmpty)
    }

    @Test fun cancellationCompletesEachCallbackOnceAndIgnoresLateTtsCallbacks() {
        val queue = SpeechCompletions()
        var finished = 0
        queue.add("arrival") { finished++ }
        queue.cancelAll()
        queue.complete("arrival")
        queue.cancelAll()
        assertEquals(1, finished)
        assertTrue(queue.isEmpty)
    }
}
