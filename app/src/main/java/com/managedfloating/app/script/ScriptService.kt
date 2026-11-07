package com.managedfloating.app.script

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.managedfloating.app.IScreenControl
import com.managedfloating.app.ManagedService
import com.managedfloating.core.script.ScriptTask
import com.managedfloating.core.script.TaskStore
import org.fellow99.nodebridge.NodeRuntime
import org.fellow99.nodebridge.NodeRuntimeOptions
import org.fellow99.nodebridge.NodeState
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ScriptService] —— 脚本执行器（独立 `:node` 进程，前台服务）。
 *
 * Node 常驻（`node::Start` 每进程一次）：appEntry 为 `runner.js`，循环向宿主拉取待执行脚本并 eval。
 * 宿主能力 `screen.*` 经 [IScreenControl] Binder 回调主进程的 ScreenController。
 * Node 崩溃不影响主进程（进程隔离）；服务重启后重新拉起 Node。
 */
class ScriptService : Service() {

    private var control: IScreenControl? = null
    private var bound = false
    private val queue = ConcurrentLinkedQueue<QueuedScript>()
    private val nodeStarted = AtomicBoolean(false)
    private val pendingDone = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()
    private val results = java.util.concurrent.ConcurrentHashMap<String, RunResult>()

    data class QueuedScript(val taskId: String, val script: String)
    data class RunResult(val ok: Boolean, val error: String?)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            control = IScreenControl.Stub.asInterface(service)
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            control = null; bound = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        bindService(Intent(this, ManagedService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        if (taskId != null) {
            // 从 TaskStore 读取任务脚本
            val store = TaskStore(storeFile())
            val task = store.get(taskId)
            if (task != null) {
                queue.add(QueuedScript(task.id, task.script))
                ensureNode()
            }
        } else {
            ensureNode()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun storeFile() = File(filesDir.parentFile, "files/tasks.json").let {
        // 与主进程一致：app filesDir/tasks.json
        File(applicationInfo.dataDir, "files/tasks.json")
    }

    private fun ensureNode() {
        if (!nodeStarted.compareAndSet(false, true)) return
        val rt = NodeRuntime.getInstance(this)
        registerHost(rt)
        val payloadRoot = File(filesDir, "node-bridge/payload")
        runCatching {
            payloadRoot.mkdirs()
            File(payloadRoot, "runner.js").writeText(RUNNER_JS)
        }
        val res = rt.start(NodeRuntimeOptions(entry = "bootstrap.js", appEntry = "runner.js"))
        notifyState("Node 启动: ${res.state}")
    }

    private fun registerHost(rt: NodeRuntime) {
        rt.host.register("script.next") {
            val q = queue.poll()
            if (q == null) """{"script":null}"""
            else {
                pendingDone[q.taskId] = CountDownLatch(1)
                val esc = q.script.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "")
                """{"script":"$esc","taskId":"${q.taskId}"}"""
            }
        }
        rt.host.register("script.done") { payload ->
            val o = JSONObject(payload)
            val id = o.optString("taskId")
            results[id] = RunResult(o.optBoolean("ok"), o.optString("error").takeIf { it.isNotEmpty() })
            pendingDone.remove(id)?.countDown()
            """{"ok":true}"""
        }
        rt.host.register("screen.tap") { p ->
            val o = JSONObject(p)
            val ok = control?.tap(o.optInt("x"), o.optInt("y")) ?: false
            """{"ok":$ok}"""
        }
        rt.host.register("screen.swipe") { p ->
            val o = JSONObject(p)
            val ok = control?.swipe(o.optInt("x1"), o.optInt("y1"), o.optInt("x2"), o.optInt("y2"), o.optInt("durMs", 300)) ?: false
            """{"ok":$ok}"""
        }
        rt.host.register("screen.launch") { p ->
            val o = JSONObject(p)
            val c = o.optString("component").takeIf { it.isNotEmpty() }
            val ok = control?.launch(o.optString("pkg"), c) ?: false
            """{"ok":$ok}"""
        }
        rt.host.register("screen.frame") { _ ->
            val bytes = control?.captureFrame()
            if (bytes == null) """{"ok":false}""" else
                """{"ok":true,"base64":"${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"}"""
        }
        rt.host.register("apps.list") { _ ->
            val json = control?.listAppsJson() ?: "[]"
            """{"ok":true,"apps":$json}"""
        }
        rt.host.register("log") { p -> com.managedfloating.core.server.MfaLog.i("Script", p); """{"ok":true}""" }
    }

    /** 阻塞等待某任务执行完成（供 REST/调度取结果）。 */
    fun awaitResult(taskId: String, timeoutMs: Long): RunResult? {
        val latch = pendingDone[taskId] ?: return results[taskId]
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return results[taskId]
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "脚本执行", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("ManagedFloatingApp 脚本")
            .setContentText("脚本执行器运行中")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun notifyState(s: String) {
        com.managedfloating.core.server.MfaLog.i("Script", s)
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        private const val CHANNEL = "mfa_script"

        /** 请求 :node 进程执行一个任务（由主进程调度器调用）。 */
        fun runTask(ctx: Context, taskId: String) {
            val i = Intent(ctx, ScriptService::class.java).putExtra(EXTRA_TASK_ID, taskId)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun ensureStarted(ctx: Context) {
            val i = Intent(ctx, ScriptService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** Node 侧常驻 runner：循环拉取脚本并 eval。 */
        private val RUNNER_JS = """
            'use strict';
            const host = require('./host.js');
            (async () => {
              for (;;) {
                try {
                  const r = await host.invoke('script.next', {});
                  if (r && r.script) {
                    try {
                      await (new Function('host', 'return (async()=>{' + r.script + '})()'))(host);
                      await host.invoke('script.done', { taskId: r.taskId, ok: true });
                    } catch (e) {
                      await host.invoke('script.done', { taskId: r.taskId, ok: false, error: String(e) });
                    }
                  }
                } catch (e) { /* ignore */ }
                await new Promise(res => setTimeout(res, 200));
              }
            })();
        """.trimIndent()
    }
}
