package org.fellow99.RPAndroid.core.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MfaLogTest {

    @Before
    fun setUp() = MfaLog.clear()

    @After
    fun tearDown() = MfaLog.clear()

    @Test
    fun `keeps entries in order`() {
        MfaLog.i("T", "a")
        MfaLog.w("T", "b")
        MfaLog.e("T", "c")
        val snap = MfaLog.snapshot()
        assertEquals(listOf("a", "b", "c"), snap.map { it.msg })
        assertEquals(listOf("I", "W", "E"), snap.map { it.level })
    }

    @Test
    fun `filters by level`() {
        MfaLog.i("T", "i1")
        MfaLog.e("T", "e1")
        MfaLog.i("T", "i2")
        val errors = MfaLog.snapshot("E")
        assertEquals(1, errors.size)
        assertEquals("e1", errors.first().msg)
    }

    @Test
    fun `ring buffer caps at 500`() {
        repeat(600) { MfaLog.d("T", "m$it") }
        val snap = MfaLog.snapshot()
        assertEquals(500, snap.size)
        // 最旧的被淘汰，保留最后 500 条（m100..m599）
        assertEquals("m100", snap.first().msg)
        assertEquals("m599", snap.last().msg)
    }

    @Test
    fun `clear empties buffer`() {
        MfaLog.i("T", "x")
        MfaLog.clear()
        assertTrue(MfaLog.snapshot().isEmpty())
    }

    @Test
    fun `error with throwable includes message`() {
        MfaLog.e("T", "boom", IllegalStateException("nope"))
        assertTrue(MfaLog.snapshot().first().msg.contains("boom"))
        assertTrue(MfaLog.snapshot().first().msg.contains("nope"))
    }
}
