package com.focusblock.app.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickBlockPolicyTest {
    @Test
    fun `sanitizes empty and duplicate package names`() {
        assertEquals(
            listOf("app.one", "app.two"),
            QuickBlockPolicy.sanitizePackages(listOf(" app.one ", "", "app.one", "app.two"))
        )
    }

    @Test
    fun `session expires at its exact end time`() {
        assertFalse(QuickBlockPolicy.isExpired(null, 100L))
        assertFalse(QuickBlockPolicy.isExpired(101L, 100L))
        assertTrue(QuickBlockPolicy.isExpired(100L, 100L))
    }

    @Test
    fun `release keeps packages that were permanently blocked before session`() {
        assertEquals(
            setOf("temporary.one", "temporary.two"),
            QuickBlockPolicy.packagesToRelease(
                "permanent,temporary.one,temporary.two",
                "permanent"
            )
        )
    }
}
