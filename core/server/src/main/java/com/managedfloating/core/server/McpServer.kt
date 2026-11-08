package com.managedfloating.core.server

import com.managedfloating.core.controller.ScreenController
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
) {

    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_NAME = "ManagedFloatingApp"
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
                "task_create", "Create/update an automation-service task (JSON: id,name,script,enabled,kind,intervalMs,cron)",
                JSONObject().put("task", prop("object", "task object")), listOf("task"),
            )
            tool(
                "task_modify",
                "Partially modify an existing task by id; runtime stats are kept. Args: id plus any editable fields (name,script,enabled,kind,intervalMs,cron,packageName)",
                JSONObject()
                    .put("id", prop("string", "task id"))
                    .put("patch", prop("object", "fields to update")),
                listOf("id", "patch"),
            )
            tool(
                "task_delete", "Delete an automation-service task by id",
                JSONObject().put("id", prop("string", "task id")), listOf("id"),
            )
            tool("task_run", "Run an automation-service task now", JSONObject().put("id", prop("string", "task id")), listOf("id"))
            tool(
                "task_docs",
                "Return the JS scripting guide: all host.invoke application-layer APIs (screen.tap/swipe/launch/frame, apps.list, log) with signatures and examples, for generating task scripts",
                JSONObject(), emptyList(),
            )
        }
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
        "task_run" -> "ok=${taskApi?.run(args.optString("id"))}"
        "task_docs" -> TaskScriptDocs.text()
        else -> "error: unknown tool $name"
    }
}
