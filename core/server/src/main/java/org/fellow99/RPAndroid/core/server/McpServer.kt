package org.fellow99.RPAndroid.core.server

import org.fellow99.RPAndroid.core.controller.KeyCodes
import org.fellow99.RPAndroid.core.controller.ScreenController
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64

/**
 * [McpServer] —— MCP (Model Context Protocol) JSON-RPC 2.0 处理器。
 *
 * 只做协议与工具分发；传输由 [ManagedServer] 提供：
 *  - Streamable HTTP: `POST /mcp`（请求/响应）
 *  - SSE: `GET /mcp/sse`（先推 endpoint 事件，再经 POST /mcp 交互）
 *
 * 支持方法：initialize、notifications/initialized、ping、tools/list、tools/call。
 */
class McpServer(
    private val controller: ScreenController,
    private val taskApi: TaskApi? = null,
    /** 额外状态提供者（会话/承载/分辨率/运行中任务等），与 REST /status 共用。 */
    private val statusProvider: (() -> Map<String, Any?>)? = null,
) {

    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_NAME = "RP自动化"
        const val SERVER_VERSION = "0.1.0"
    }

    /** 处理一条 JSON-RPC 请求，返回响应 JSON（通知类返回 null）。 */
    fun handle(requestJson: String): String? {
        val req = runCatching { JSONObject(requestJson) }.getOrNull() ?: return rpcError(null, -32700, "parse error")
        val id = req.opt("id")
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()

        // 通知（无 id）不响应
        val isNotification = !req.has("id")
        if (isNotification) return null

        return when (method) {
            "initialize" -> rpcResult(id, JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JSONObject().put("tools", JSONObject()))
                put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION))
            })
            "ping" -> rpcResult(id, JSONObject())
            "tools/list" -> rpcResult(id, JSONObject().put("tools", tools()))
            "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val result = runCatching { callTool(name, args) }.getOrElse { "error: ${it.message}" }
                val isErr = result.startsWith("error:") || result.startsWith("ok=false")
                rpcResult(id, JSONObject().apply {
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "text"); put("text", result)
                    }))
                    put("isError", isErr)
                })
            }
            else -> rpcError(id, -32601, "method not found: $method")
        }
    }

    private fun rpcResult(id: Any?, result: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()

    private fun rpcError(id: Any?, code: Int, message: String): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message)).toString()

    // ---- 工具定义 ----

    private fun tools(): JSONArray {
        val arr = JSONArray()
        fun tool(name: String, desc: String, props: JSONObject, required: List<String>) {
            arr.put(JSONObject().apply {
                put("name", name)
                put("description", desc)
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", props)
                    put("required", JSONArray(required))
                })
            })
        }
        fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

        tool("screen_capture", "Capture current hosted screen as base64 PNG", JSONObject(), emptyList())
        tool("ui_read", "Read the hosted app UI element tree (JSON; requires accessibility service)", JSONObject(), emptyList())
        tool(
            "screen_tap", "Tap at virtual-screen coordinates",
            JSONObject().put("x", prop("integer", "x")).put("y", prop("integer", "y")),
            listOf("x", "y"),
        )
        tool(
            "screen_swipe", "Swipe on virtual screen",
            JSONObject().put("x1", prop("integer", "start x")).put("y1", prop("integer", "start y"))
                .put("x2", prop("integer", "end x")).put("y2", prop("integer", "end y"))
                .put("durMs", prop("integer", "duration ms (default 300)")),
            listOf("x1", "y1", "x2", "y2"),
        )
        tool(
            "screen_key", "Inject a key event (back/home/enter/del/paste/tab/esc/... or raw keycode)",
            JSONObject()
                .put("code", prop("integer", "KeyEvent keycode, e.g. 4=BACK; takes precedence"))
                .put("name", prop("string", "key name: " + KeyCodes.names().joinToString("/"))),
            emptyList(),
        )
        tool(
            "screen_text", "Type text into the focused input on the virtual screen (supports Chinese; ASCII uses input text, non-ASCII uses clipboard+paste)",
            JSONObject().put("text", prop("string", "text to type")),
            listOf("text"),
        )
        tool(
            "status", "Session status: sessionActive, hosted app, virtual screen size/dpi, running task, accessibility, mode (A=virtual/B=main)",
            JSONObject(), emptyList(),
        )
        tool(
            "switch_mode",
            "Switch hosting mode: B->A (release projection, hosted app back to virtual screen) is allowed directly; " +
                "A->B requires MediaProjection authorization via the App UI (cannot be silent), so A->B returns an error telling the user to tap Main-screen in the app",
            JSONObject().put("mode", prop("string", "target mode: A or B")),
            listOf("mode"),
        )
        tool(
            "app_close", "Close the currently hosted app on the virtual screen (keeps the session, returns to app list)",
            JSONObject(), emptyList(),
        )
        tool(
            "app_list", "List installed launchable apps", JSONObject(), emptyList(),
        )
        tool(
            "app_launch", "Launch an app onto the virtual display",
            JSONObject().put("packageName", prop("string", "app package")).put("component", prop("string", "optional component")),
            listOf("packageName"),
        )
        if (taskApi != null) {
            tool("task_list", "List automation-service tasks", JSONObject(), emptyList())
            tool(
                "task_create", "Create/update an automation-service task (JSON: id,name,script,enabled,kind,intervalSec,runAt)",
                JSONObject().put("task", prop("object", "task object")), listOf("task"),
            )
            tool(
                "task_modify",
                "Partially modify an existing task by id; runtime stats are kept. Args: id plus any editable fields (name,script,enabled,kind,intervalSec,runAt,packageName)",
                JSONObject()
                    .put("id", prop("string", "task id"))
                    .put("patch", prop("object", "fields to update")),
                listOf("id", "patch"),
            )
            tool(
                "task_delete", "Delete an automation-service task by id",
                JSONObject().put("id", prop("string", "task id")), listOf("id"),
            )
            tool(
                "task_get", "Get one automation-service task by id (includes lastRunAt/nextRunAt/runCount/lastStatus)",
                JSONObject().put("id", prop("string", "task id")), listOf("id"),
            )
            tool(
                "task_run", "Run an automation-service task now. Optional waitMs waits for completion and returns lastStatus/runCount",
                JSONObject().put("id", prop("string", "task id"))
                    .put("waitMs", prop("integer", "wait for completion up to N ms (default 0 = return immediately)")),
                listOf("id"),
            )
            tool(
                "task_stop", "Stop an automation-service task: cancel its scheduled alarm and signal the running script to abort",
                JSONObject().put("id", prop("string", "task id")), listOf("id"),
            )
            tool(
                "task_docs",
                "Return the JS scripting guide: host.invoke APIs (screen.tap/swipe/launch/frame/key/text/info, apps.list, log) with signatures and examples, for generating task scripts",
                JSONObject(), emptyList(),
            )
        }
        tool(
            "logs", "Read the unified log buffer (main process, includes script logs). Args: level? limit? clear?",
            JSONObject()
                .put("level", prop("string", "filter level: I/W/E/D"))
                .put("limit", prop("integer", "max entries, default 200"))
                .put("clear", prop("boolean", "clear the buffer instead of reading")),
            emptyList(),
        )
        return arr
    }

    private fun callTool(name: String, args: JSONObject): String = when (name) {
        "screen_capture" -> {
            val bytes = controller.captureFrame()
            if (bytes == null) "no frame" else Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        "ui_read" -> controller.readUi() ?: "ui unavailable (enable accessibility service)"
        "screen_tap" -> {
            if (!args.has("x") || !args.has("y")) "error: missing required arguments 'x','y'"
            else "ok=${controller.tap(args.optInt("x"), args.optInt("y"))}"
        }
        "screen_swipe" -> {
            val need = listOf("x1", "y1", "x2", "y2")
            if (need.any { !args.has(it) }) "error: missing required arguments $need"
            else "ok=${controller.swipe(
                args.optInt("x1"), args.optInt("y1"), args.optInt("x2"), args.optInt("y2"),
                args.optInt("durMs", 300),
            )}"
        }
        "app_list" -> {
            val arr = JSONArray()
            controller.listApps().forEach {
                arr.put(JSONObject().put("packageName", it.packageName).put("label", it.label))
            }
            arr.toString()
        }
        "screen_key" -> {
            val code = if (args.has("code")) args.optInt("code") else KeyCodes.byName(args.optString("name"))
            if (code <= 0) {
                "error: missing required argument 'code' or unknown key 'name' (known: ${KeyCodes.names().joinToString("/")})"
            } else {
                "ok=${controller.pressKey(code)} code=$code"
            }
        }
        "screen_text" -> {
            if (!args.has("text")) "error: missing required argument 'text'"
            else "ok=${controller.text(args.optString("text"))}"
        }
        "status" -> statusJson()
        "switch_mode" -> {
            val ctl = controller as? org.fellow99.RPAndroid.core.controller.AppScreenController
            if (ctl == null) "error: unsupported controller (switch_mode requires AppScreenController)"
            else {
                val target = org.fellow99.RPAndroid.core.controller.DisplayMode.fromWire(args.optString("mode"))
                if (target == org.fellow99.RPAndroid.core.controller.DisplayMode.MAIN) {
                    "ok=false A->B requires MediaProjection authorization via App UI (无法静默授权); tap Main-screen in the app"
                } else {
                    val denied = ctl.switchDisplayMode(target)
                    if (denied != null) "ok=false $denied" else "ok=true mode=${ctl.displayMode.wire}"
                }
            }
        }
        "app_close" -> {
            val ctl = controller
            if (ctl is org.fellow99.RPAndroid.core.controller.AppScreenController) {
                runCatching { ctl.closeHostedApp() }.fold(
                    onSuccess = { "ok=true" },
                    onFailure = { "error: ${it.message}" },
                )
            } else {
                "error: unsupported controller (app_close requires AppScreenController)"
            }
        }
        "app_launch" -> {
            val pkg = args.optString("packageName").trim()
            if (pkg.isEmpty()) "error: missing required argument 'packageName'"
            else {
                val r = controller.launch(
                    pkg,
                    args.optString("component").takeIf { it.isNotEmpty() },
                )
                "ok=${r.ok} ${r.message}"
            }
        }
        "task_list" -> taskApi?.listJson() ?: "[]"
        "task_create" -> {
            val t = args.optJSONObject("task")
            if (t == null) "error: missing task" else "ok=${taskApi?.upsert(t.toString())}"
        }
        "task_modify" -> {
            val id = args.optString("id").trim()
            val patch = args.optJSONObject("patch")
            when {
                id.isEmpty() -> "error: missing required argument 'id'"
                patch == null -> "error: missing required argument 'patch'"
                else -> {
                    val ok = taskApi?.modify(id, patch.toString()) == true
                    if (ok) "ok=true modified $id" else "error: task not found: $id"
                }
            }
        }
        "task_delete" -> {
            val id = args.optString("id").trim()
            if (id.isEmpty()) "error: missing required argument 'id'"
            else "ok=${taskApi?.delete(id)} deleted $id"
        }
        "task_get" -> {
            val id = args.optString("id").trim()
            if (id.isEmpty()) "error: missing required argument 'id'"
            else taskApi?.getJson(id) ?: "error: task not found: $id"
        }
        "task_run" -> {
            val id = args.optString("id").trim()
            if (id.isEmpty()) "error: missing required argument 'id'"
            else {
                val started = taskApi?.run(id) == true
                if (!started) "ok=false run rejected (task not found, or another task is running)"
                else {
                    val waitMs = args.optLong("waitMs", 0L)
                    if (waitMs <= 0L) {
                        "ok=true started $id"
                    } else {
                        val finished = taskApi?.waitRun(id, waitMs) == true
                        val t = taskApi?.getJson(id) ?: "null"
                        "ok=true finished=$finished task=$t"
                    }
                }
            }
        }
        "task_stop" -> {
            val id = args.optString("id").trim()
            if (id.isEmpty()) "error: missing required argument 'id'"
            else "ok=${taskApi?.stop(id)} stopped $id"
        }
        "logs" -> {
            if (args.optBoolean("clear", false)) {
                MfaLog.clear()
                "ok=true cleared"
            } else {
                val level = args.optString("level").takeIf { it.isNotEmpty() }
                val limit = args.optInt("limit", 200).coerceIn(1, 500)
                val arr = JSONArray()
                MfaLog.snapshot(level).takeLast(limit).forEach {
                    arr.put(
                        JSONObject().put("time", it.time).put("level", it.level)
                            .put("tag", it.tag).put("msg", it.msg),
                    )
                }
                arr.toString()
            }
        }
        "task_docs" -> TaskScriptDocs.text()
        else -> "error: unknown tool $name"
    }

    /** 状态 JSON：与 REST /status 同源（statusProvider + 控制器/承载信息）。 */
    private fun statusJson(): String {
        val base = linkedMapOf<String, Any?>(
            "sessionActive" to controller.isSessionActive(),
            "hostedApp" to (controller as? org.fellow99.RPAndroid.core.controller.AppScreenController)
                ?.hosted()?.let { linkedMapOf("packageName" to it.packageName, "label" to it.label) },
        )
        statusProvider?.invoke()?.forEach { (k, v) -> base[k] = v }
        return JSONObject(base as Map<*, *>).toString()
    }
}
