package moe.hellorename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationRegistryTest {

    @Test
    fun `old generation is discarded after a new share`() {
        val registry = OperationRegistry()
        val old = registry.begin()
        assertTrue(registry.isCurrent(old))

        val new = registry.begin()
        assertFalse("旧复制/旧保存的结果必须被丢弃", registry.isCurrent(old))
        assertTrue(registry.isCurrent(new))
        assertEquals(new, registry.current())
    }

    @Test
    fun `invalidate makes the current generation stale`() {
        val registry = OperationRegistry()
        val token = registry.begin()
        val next = registry.invalidate()
        assertFalse(registry.isCurrent(token))
        assertTrue(registry.isCurrent(next))
    }

    @Test
    fun `reset only affects tests`() {
        val registry = OperationRegistry()
        registry.begin()
        registry.resetForTest()
        assertEquals(0L, registry.current())
        assertFalse(registry.isCurrent(1L))
    }
}
