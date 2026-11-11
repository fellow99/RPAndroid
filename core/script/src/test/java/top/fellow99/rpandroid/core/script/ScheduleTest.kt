package top.fellow99.rpandroid.core.script

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 手动「立即执行一次」后的续排规则（回归缺陷：定时任务手动执行一次后 nextRunAt 被清零，
 * 但闹钟仍在，导致到点又执行一次；定时任务点“启动/执行”又当场就跑）。
 */
class ScheduleTest {

    private val now = 1_800_000_000_000L

    private fun task(
        kind: ScheduleKind,
        runAt: Long = 0,
        intervalSec: Long = 0,
        enabled: Boolean = true,
    ) = ScriptTask(id = "t", name = "t", script = "1", enabled = enabled, kind = kind, intervalSec = intervalSec, runAt = runAt)

    @Test
    fun `scheduled future runAt is preserved after a manual run`() {
        val runAt = now + 120_000
        assertEquals(runAt, nextRunAfterManualRun(task(ScheduleKind.SCHEDULED, runAt = runAt), now))
    }

    @Test
    fun `scheduled past runAt is cleared`() {
        val runAt = now - 1
        assertEquals(0L, nextRunAfterManualRun(task(ScheduleKind.SCHEDULED, runAt = runAt), now))
    }

    @Test
    fun `disabled task never schedules`() {
        val runAt = now + 120_000
        assertEquals(0L, nextRunAfterManualRun(task(ScheduleKind.SCHEDULED, runAt = runAt, enabled = false), now))
        assertEquals(0L, nextRunAfterManualRun(task(ScheduleKind.INTERVAL, intervalSec = 60, enabled = false), now))
    }

    @Test
    fun `immediate task has no next run`() {
        assertEquals(0L, nextRunAfterManualRun(task(ScheduleKind.IMMEDIATE), now))
    }

    @Test
    fun `interval continues from manual run`() {
        assertEquals(now + 60_000, nextRunAfterManualRun(task(ScheduleKind.INTERVAL, intervalSec = 60), now))
        assertEquals(0L, nextRunAfterManualRun(task(ScheduleKind.INTERVAL, intervalSec = 0), now))
    }

    @Test
    fun `cron schedules the next occurrence`() {
        val next = nextRunAfterManualRun(task(ScheduleKind.CRON, runAt = 0).copy(cron = "0 * * * * *"), now)
        assertEquals(true, next > now)
        assertEquals(0L, nextRunAfterManualRun(task(ScheduleKind.CRON).copy(cron = "bad"), now))
    }
}
