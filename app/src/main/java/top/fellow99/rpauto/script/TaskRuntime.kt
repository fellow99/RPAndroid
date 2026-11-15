package top.fellow99.rpauto.script

import android.content.Context
import top.fellow99.rpauto.core.script.TaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * [TaskRuntime] —— 服务端可见的「当前自动化执行状态」（008-client）。
 *
 * - 记录全局**唯一**的正在执行任务 id（互斥：`begin` 在已有执行时返回 false，"同时最多一个"）。
 * - 由 REST/自动化 UI 在 run/stop 时调用 [begin]/[end]；进程内职责同 App UI 的
 *   `PresentationHub.runningTaskId`，但服务端（`/status`）可读，供 /client 展示执行状态。
 * - 内置 watchdog：运行中轮询 `:node` 结果文件（`mfa-run/<id>.json`），完成后把
 *   `lastStatus` 回写进 TaskStore 并清空 runningTaskId（原来只有 App UI 轮询才回写）。
 * - [begin] 前会清除该任务此前遗留的（未被消费的）结果文件，避免 watchdog 把旧结果
 *   误判为"本次运行已完成"而立刻清掉执行状态（008 联调 bug 修复）。
 *
 * 注：App UI（AutomationScreen）仍用 PresentationHub 做进程内互斥，两边各自互斥（008 已知限制）；
 * 08 client 侧以本对象为服务端权威。
 */
object TaskRuntime {

    /** 当前正在执行的自动化任务 id；null = 无。 */
    @Volatile
    var runningTaskId: String? = null
        private set

    private var ctxRef: Context? = null

    @Volatile
    private var watchdogStarted = false

    private val scopeLock = Any()
    private var scope: CoroutineScope? = null

    /** 绑定宿主 Context（在 TaskApiImpl 构造时调用一次）。 */
    fun attach(context: Context) {
        ctxRef = context.applicationContext
    }

    /** 尝试占用执行权：当前无执行中任务则记录 id 并启动 watchdog，返回 true；否则返回 false。 */
    @Synchronized
    fun begin(taskId: String): Boolean {
        if (runningTaskId != null) return false
        // 清掉该任务此前遗留的（未被消费的）结果文件，避免 watchdog 误把旧结果当作本次运行完成。
        ctxRef?.let { ScriptService.clearRunOutcome(it, taskId) }
        runningTaskId = taskId
        ensureWatchdog()
        return true
    }

    /** 释放执行权（停止/完成）。仅当当前执行者匹配才清空。 */
    fun end(taskId: String) {
        if (runningTaskId == taskId) runningTaskId = null
    }

    private fun ensureWatchdog() {
        if (watchdogStarted) return
        synchronized(scopeLock) {
            if (watchdogStarted) return
            watchdogStarted = true
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = s
            s.launch {
                while (true) {
                    val id = runningTaskId
                    val ctx = ctxRef
                    if (id != null && ctx != null) {
                        val out = ScriptService.takeRunOutcome(ctx, id)
                        if (out != null) {
                            // 完成：回写 lastStatus 并释放执行权
                            runCatching {
                                val store = TaskStore(File(ctx.applicationInfo.dataDir, "files/tasks.json"))
                                store.get(id)?.let { cur ->
                                    store.upsert(
                                        cur.copy(lastStatus = if (out.ok) "ok" else "error: ${out.error}"),
                                    )
                                }
                            }
                            end(id)
                        }
                    } else if (id != null && ctx == null) {
                        end(id)
                    }
                    delay(1000)
                }
            }
        }
    }
}
