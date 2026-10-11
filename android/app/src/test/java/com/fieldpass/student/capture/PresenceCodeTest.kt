package com.fieldpass.student.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresenceCodeTest {
    @Test fun acceptsOnlyFp1StringsWithinTheServerLimit() {
        val debounce = PresenceCodeDebounce()
        assertNull(debounce.next(""))
        assertNull(debounce.next("https://example.com"))
        assertNull(debounce.next("FP1.1.abc"))
        assertNull(debounce.next("x".repeat(500)))
        assertNull(debounce.next("fp1." + "x".repeat(197)))
        assertEquals("fp1.176000000.AbCd", debounce.next("fp1.176000000.AbCd"))
        assertEquals("fp1." + "x".repeat(196), debounce.next("fp1." + "x".repeat(196)))
    }

    @Test fun eachStringIsHandedOutOnce() {
        val debounce = PresenceCodeDebounce()
        assertEquals("fp1.1.a", debounce.next("fp1.1.a"))
        assertNull(debounce.next("fp1.1.a"))
        assertEquals("fp1.2.b", debounce.next("fp1.2.b"))
        assertNull(debounce.next("fp1.1.a"))
    }

    @Test fun aFailedSendMayGoOutAgainAFewTimes() {
        val debounce = PresenceCodeDebounce()
        assertEquals("fp1.1.a", debounce.next("fp1.1.a"))
        repeat(PresenceCodeDebounce.MAX_RETRIES) {
            debounce.sendFailed("fp1.1.a")
            assertEquals("fp1.1.a", debounce.next("fp1.1.a"))
        }
        debounce.sendFailed("fp1.1.a") // retries used up: wait for the next code on the screen
        assertNull(debounce.next("fp1.1.a"))
    }
}
