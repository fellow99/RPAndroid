package top.fellow99.rpandroid.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CronTest {

    private fun cal(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    @Test
    fun `every 5 minutes lands on correct minute`() {
        val from = cal(2026, 11, 7, 10, 0)
        val next = Cron.next("*/5 * * * *", from)!!
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(5, c.get(Calendar.MINUTE))
        assertEquals(10, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `daily at fixed hour`() {
        val from = cal(2026, 11, 7, 6, 0)
        val next = Cron.next("30 9 * * *", from)!!
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(9, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, c.get(Calendar.MINUTE))
    }

    @Test
    fun `rolls to next day when time passed`() {
        // 10:00 之后找当天 9:30，应落到次日
        val from = cal(2026, 11, 7, 10, 0)
        val next = Cron.next("30 9 * * *", from)!!
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(8, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, c.get(Calendar.MINUTE))
    }

    @Test
    fun `specific month and day of month`() {
        val from = cal(2026, 1, 1, 0, 0)
        val next = Cron.next("0 0 25 12 *", from)!!
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.DECEMBER, c.get(Calendar.MONTH))
        assertEquals(25, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `day of week 0 and 7 both mean Sunday`() {
        val from = cal(2026, 11, 7, 0, 0) // 2026-11-07 is a Saturday
        val next0 = Cron.next("0 12 * * 0", from)!!
        val next7 = Cron.next("0 12 * * 7", from)!!
        assertEquals(next0, next7)
        val c = Calendar.getInstance().apply { timeInMillis = next0 }
        assertEquals(Calendar.SUNDAY, c.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `result is always strictly after from`() {
        val from = cal(2026, 11, 7, 12, 30)
        val next = Cron.next("* * * * *", from)!!
        assertTrue(next > from)
    }

    @Test
    fun `invalid field returns null`() {
        assertNull(Cron.next("99 * * * *", cal(2026, 11, 7, 0, 0)))
    }

    @Test
    fun `too few fields returns null`() {
        assertNull(Cron.next("* * *", 0L))
    }

    @Test
    fun `range and list syntax`() {
        val from = cal(2026, 11, 7, 0, 0)
        // 仅在 9,10,11 点的第 0 分
        val next = Cron.next("0 9-11 * * *", from)!!
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(9, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, c.get(Calendar.MINUTE))
    }

    @Test
    fun `six fields with seconds fires on next minute at second 0`() {
        val from = cal(2026, 11, 7, 12, 30) + 15_000 // 12:30:15
        val next = Cron.next("0 * * * * *", from)!!
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(12, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(31, c.get(Calendar.MINUTE))
        assertEquals(0, c.get(Calendar.SECOND))
    }

    @Test
    fun `six fields can match later second in same minute`() {
        val from = cal(2026, 11, 7, 12, 30) + 15_000 // 12:30:15
        val next = Cron.next("30 * * * * *", from)!!
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(12, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, c.get(Calendar.MINUTE))
        assertEquals(30, c.get(Calendar.SECOND))
    }

    @Test
    fun `six fields result is strictly after from`() {
        val from = cal(2026, 11, 7, 12, 30) + 30_000 // 12:30:30\.000
        val next = Cron.next("30 * * * * *", from)!!
        assertTrue(next > from)
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(31, c.get(Calendar.MINUTE))
        assertEquals(30, c.get(Calendar.SECOND))
    }

    @Test
    fun `more than six fields returns null`() {
        assertNull(Cron.next("0 0 * * * * *", cal(2026, 11, 7, 0, 0)))
    }

    @Test
    fun `five fields still minute granularity`() {
        val from = cal(2026, 11, 7, 12, 30) + 15_000
        val next = Cron.next("* * * * *", from)!!
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(31, c.get(Calendar.MINUTE))
        assertEquals(0, c.get(Calendar.SECOND))
    }
}
