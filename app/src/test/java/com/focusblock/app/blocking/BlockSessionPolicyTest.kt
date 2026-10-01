package com.focusblock.app.blocking

import org.junit.Assert.*
import org.junit.Test

class BlockSessionPolicyTest {
    @Test fun `timed and manual sessions survive absent UI timer`() {
        assertTrue(BlockSessionPolicy.phase(0, null, 999999, 0, 5, 1).blocking)
        assertFalse(BlockSessionPolicy.phase(0, 100, 100, 0, 5, 1).blocking)
    }
    @Test fun `cycle boundaries alternate focus and rest without final rest`() {
        val end = 115 * 60000L
        assertTrue(BlockSessionPolicy.phase(0, end, 0, 25, 5, 4).blocking)
        assertFalse(BlockSessionPolicy.phase(0, end, 25 * 60000, 25, 5, 4).blocking)
        assertEquals(2, BlockSessionPolicy.phase(0, end, 30 * 60000, 25, 5, 4).round)
        assertTrue(BlockSessionPolicy.phase(0, end, 114 * 60000, 25, 5, 4).blocking)
        assertFalse(BlockSessionPolicy.phase(0, end, end, 25, 5, 4).blocking)
    }
    @Test fun `exception is package scoped bounded and expires exactly`() {
        assertEquals(200L, BlockSessionPolicy.exceptionEnd(100, 200))
        assertEquals(120100L, BlockSessionPolicy.exceptionEnd(100, null))
        assertTrue(BlockSessionPolicy.exceptionApplies("a", "a", 200, 199))
        assertFalse(BlockSessionPolicy.exceptionApplies("b", "a", 200, 199))
        assertFalse(BlockSessionPolicy.exceptionApplies("a", "a", 200, 200))
    }
    @Test fun `extended final cycle does not reopen apps`() {
        assertTrue(BlockSessionPolicy.phase(0, 130 * 60000, 121 * 60000, 25, 5, 4).blocking)
    }
}
