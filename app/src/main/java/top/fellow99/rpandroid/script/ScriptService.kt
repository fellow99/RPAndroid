package top.fellow99.rpandroid.script

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
import top.fellow99.rpandroid.IScreenControl
import top.fellow99.rpandroid.ManagedService
import top.fellow99.rpandroid.core.script.ScriptTask
import top.fellow99.rpandroid.core.script.TaskStore
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
 * [ScriptService] —— **自动化服务**执行器（独立 `:node` 进程，前台服务）。
 *
 * 注：类名沿用历史符号 `ScriptService`，产品术语统一称为「自动化服务」。
 * Node 常驻（`node::Start` 每进程一次）：appEntry 为 `runner.js`，循环向宿主拉取待执行的自动化任务（JS）并 eval。
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
            // 从 TaskStore 读取自动化任务
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
            val ok = o.optBoolean("ok")
            val err = o.optString("error").takeIf { it.isNotEmpty() }
            results[id] = RunResult(ok, err)
            pendingDone.remove(id)?.countDown()
            // 回传主进程统一日志（:node 进程的 MfaLog 独立，不聚合到 /logs）
            control?.log("Script", "run done task=$id ok=$ok${if (err != null) " err=$err" else ""}")
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
        // 按键注入：`{code}` 优先，否则按名称查表（与 MCP screen_key 一致）。
        rt.host.register("screen.key") { p ->
            val o = JSONObject(p)
            val code = if (o.has("code")) o.optInt("code")
            else top.fellow99.rpandroid.core.controller.KeyCodes.byName(o.optString("name"))
            val ok = if (code > 0) control?.key(code) ?: false else false
            """{"ok":$ok,"code":$code}"""
        }
        // 文本注入：中文走剪贴板+粘贴（主进程实现）。
        rt.host.register("screen.text") { p ->
            val o = JSONObject(p)
            val ok = control?.text(o.optString("text")) ?: false
            """{"ok":$ok}"""
        }
        // 屏幕/会话信息：脚本按真实分辨率算坐标，不再写死。
        rt.host.register("screen.info") { _ -> control?.info() ?: """{"ok":false}""" }
        rt.host.register("log") { p ->
            // 关键：转发到主进程日志缓冲，REST /logs 与 MCP logs 可见（tag=Script）
            control?.log("Script", p)
            """{"ok":true}"""
        }
    }

    /** 阻塞等待某任务执行完成（供 REST/调度取结果）。 */
    fun awaitResult(taskId: String, timeoutMs: Long): RunResult? {
        val latch = pendingDone[taskId] ?: return results[taskId]
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return results[taskId]
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "自动化服务", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("RP自动化 自动化服务")
            .setContentText("自动化服务运行中")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun notifyState(s: String) {
        // 优先回传主进程（/logs 可见）；未绑定成功时退回本进程日志
        val c = control
        if (c != null) runCatching { c.log("Script", s) }.onFailure {
            top.fellow99.rpandroid.core.server.MfaLog.i("Script", s)
        } else {
            top.fellow99.rpandroid.core.server.MfaLog.i("Script", s)
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        private const val CHANNEL = "mfa_script"

        /** 停止信号目录（与 runner.js 的相对路径 __dirname/../../mfa-stop 对应）。 */
        private fun stopFlagFile(ctx: Context, taskId: String): File {
            val files = File(ctx.applicationInfo.dataDir, "files")
            return File(files, "mfa-stop/${taskId}.flag")
        }

        /** 运行结果文件：:node runner 完成后写入，主进程读后即删（单次消费）。 */
        private fun runOutcomeFile(ctx: Context, taskId: String): File {
            val files = File(ctx.applicationInfo.dataDir, "files")
            return File(files, "mfa-run/$taskId.json")
        }

        /** 请求 :node 进程中止某任务的当前执行（写停止信号文件；主进程侧调用）。 */
        fun requestStop(ctx: Context, taskId: String) {
            runCatching {
                val f = stopFlagFile(ctx, taskId)
                f.parentFile?.mkdirs()
                f.writeText("stop")
            }
        }

        /**
         * 清除遗留的停止信号。
         *
         * 必须在每次新执行开始前调用：`task_stop` 在任务未运行时也会写停止信号，若不清除，
         * 下一次执行会在第一个 `host.invoke` 处被误判为“已取消”（真机实测）。
         */
        fun clearStopFlag(ctx: Context, taskId: String) {
            runCatching { stopFlagFile(ctx, taskId).delete() }
        }

        /** 读取 :node runner 报告的某任务执行结果（有则消费并删除）。 */
        fun takeRunOutcome(ctx: Context, taskId: String): RunOutcome? {
            val f = runOutcomeFile(ctx, taskId)
            if (!f.exists()) return null
            return runCatching {
                val o = JSONObject(f.readText())
                RunOutcome(o.optBoolean("ok"), o.optString("error").takeIf { it.isNotEmpty() })
            }.getOrNull().also { runCatching { f.delete() } }
        }

        /** 删除某任务残留的结果文件（开始新一次执行前去重，供 TaskRuntime.begin 使用）。 */
        fun clearRunOutcome(ctx: Context, taskId: String) {
            runCatching { runOutcomeFile(ctx, taskId).delete() }
        }

        /** 请求 :node 进程执行一个任务（由主进程调度器调用）。 */
        fun runTask(ctx: Context, taskId: String) {
            val i = Intent(ctx, ScriptService::class.java).putExtra(EXTRA_TASK_ID, taskId)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun ensureStarted(ctx: Context) {
            val i = Intent(ctx, ScriptService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** Node 侧常驻 runner：循环拉取自动化任务（JS）并 eval。
         * 支持停止信号：主进程写 filesDir/mfa-stop/<taskId>.flag，runner 在拉取到任务后及每次
         * `host.invoke` 前检查，命中则中止当前脚本并上报 cancelled（尽力而为，无法硬中断同步长任务）。 */
        private val RUNNER_JS = """
            'use strict';
            const host = require('./host.js');
            const fs = require('fs');
            const path = require('path');
            const STOP_DIR = path.join(__dirname, '..', '..', 'mfa-stop');
            const RUN_DIR = path.join(__dirname, '..', '..', 'mfa-run');
            let currentTaskId = null;

            function stopRequested() {
              if (!currentTaskId) return false;
              try { return fs.existsSync(path.join(STOP_DIR, currentTaskId + '.flag')); } catch (e) { return false; }
            }
            function clearStop() {
              if (!currentTaskId) return;
              try { fs.unlinkSync(path.join(STOP_DIR, currentTaskId + '.flag')); } catch (e) {}
            }
            function writeOutcome(taskId, ok, error) {
              if (!taskId) return;
              try {
                fs.mkdirSync(RUN_DIR, { recursive: true });
                fs.writeFileSync(path.join(RUN_DIR, taskId + '.json'), JSON.stringify({ ok: !!ok, error: error || null, at: Date.now() }));
              } catch (e) {}
            }

            const hosted = {};
            for (const k of Object.keys(host)) hosted[k] = host[k];
            const guardedInvoke = async (api, args) => {
              if (stopRequested()) { throw new Error('cancelled by user'); }
              return await host.invoke(api, args);
            };
            const hostProxy = new Proxy(hosted, {
              get(t, prop) {
                if (prop === 'invoke') return guardedInvoke;
                return t[prop];
              },
            });

            (async () => {
              for (;;) {
                try {
                  const r = await host.invoke('script.next', {});
                  if (r && r.script) {
                    currentTaskId = r.taskId;
                    if (!stopRequested()) {
                      try {
                        await (new Function('host', 'return (async()=>{' + r.script + '})()'))(hostProxy);
                        await host.invoke('script.done', { taskId: r.taskId, ok: true });
                        writeOutcome(r.taskId, true, null);
                      } catch (e) {
                        await host.invoke('script.done', { taskId: r.taskId, ok: false, error: String(e) });
                        writeOutcome(r.taskId, false, String(e));
                      }
                    } else {
                      await host.invoke('script.done', { taskId: r.taskId, ok: false, error: 'cancelled by user' });
                      writeOutcome(r.taskId, false, 'cancelled by user');
                    }
                    clearStop();
                    currentTaskId = null;
                  }
                } catch (e) { /* ignore */ }
                await new Promise(res => setTimeout(res, 200));
              }
            })();
        """.trimIndent()
    }
}

/** 自动化任务一次执行的结果（:node runner 写主进程读）。 */
data class RunOutcome(val ok: Boolean, val error: String?)
