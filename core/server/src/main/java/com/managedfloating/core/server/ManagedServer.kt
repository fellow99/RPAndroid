package com.managedfloating.core.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.managedfloating.core.controller.ScreenController
import com.managedfloating.core.shell.NoRootShell
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondTextWriter
import io.ktor.server.request.receiveText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ManagedServer] —— 对外 REST 接口（架构 D）。
 *
 * 复用 [ScreenController] 作为唯一操作入口。[shell]（NoRootShell）用于免 root 自举调试。
 * 局域网直连 / USB(adb forward) 接入。
 */
class ManagedServer(
    private val controller: ScreenController,
    private val port: Int,
    private val shell: NoRootShell? = null,
    private val appContext: android.content.Context? = null,
    private val authToken: String = "",
    private val taskApi: TaskApi? = null,
    /** 额外状态提供者（如无障碍是否启用），由 app 层注入。 */
    private val statusProvider: (() -> Map<String, Any?>)? = null,
) {

    private val mapper = jacksonObjectMapper()
    private val mcp = McpServer(controller, taskApi)
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.channels.Channel<String>>()
    private val streamClients = java.util.concurrent.atomic.AtomicInteger(0)

    /** 007-webrtc 推流（惰性；无 appContext 时禁用）。输入统一经 ScreenController（C-1）。 */
    private val webrtc: com.managedfloating.core.webrtc.WebRtcPush? by lazy {
        appContext?.let { ctx ->
            com.managedfloating.core.webrtc.WebRtcPush(ctx, controller).also { p ->
                p.onInput = { txt -> WebRtcWire.handleInput(controller, txt) }
            }
        }
    }

    fun start() {
        if (running != null) return
        val s = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) { jackson() }
            install(WebSockets)
            install(createApplicationPlugin("TokenAuth") {
                onCall { call ->
                    val path = call.request.local.uri
                    if (authToken.isNotEmpty() && path != "/health" && !path.startsWith("/debug/")) {
                        val bearer = call.request.headers["Authorization"]
                            ?.removePrefix("Bearer")?.trim()
                        val got = call.request.headers[ServerConfig.HEADER]
                            ?: bearer
                            ?: call.request.queryParameters["token"]
                        if (got != authToken) {
                            // 明确声明 Bearer 方案（非 OAuth），避免 MCP 客户端退化为动态客户端注册(DCR)
                            call.response.headers.append("WWW-Authenticate", "Bearer realm=ManagedFloatingApp")
                            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                        }
                    }
                }
            })
            routing {
                get("/health") {
                    call.respond(
                        mapOf(
                            "ok" to true,
                            "service" to "ManagedFloatingApp",
                            "port" to port,
                            "authEnabled" to authToken.isNotEmpty(),
                        ),
                    )
                }

                get("/status") {
                    val base = linkedMapOf<String, Any?>(
                        "sessionActive" to controller.isSessionActive(),
                        "port" to port,
                        "authEnabled" to authToken.isNotEmpty(),
                        "streamClients" to streamClients.get(),
                        "webrtcPeers" to (webrtc?.activePeers() ?: -1),
                        "sdkInt" to android.os.Build.VERSION.SDK_INT,
                        "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    )
                    statusProvider?.invoke()?.forEach { (k, v) -> base[k] = v }
                    call.respond(base)
                }

                get("/logs") {
                    val level = call.request.queryParameters["level"]?.uppercase()
                    call.respond(MfaLog.snapshot(level).map {
                        mapOf("time" to it.time, "level" to it.level, "tag" to it.tag, "msg" to it.msg)
                    })
                }

                post("/logs/clear") {
                    MfaLog.clear()
                    call.respond(mapOf("ok" to true))
                }

                get("/apps") {
                    val apps = withContext(Dispatchers.IO) { controller.listApps() }
                    call.respond(apps)
                }

                // 应用图标（008-client 应用/自动化抽屉）：PNG + 进程内缓存；未知包 404。
                get("/apps/{pkg}/icon") {
                    val pkg = call.parameters["pkg"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no pkg"))
                    val ctx = appContext
                    if (ctx == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "icon unavailable (no app context)"))
                        return@get
                    }
                    val bytes = withContext(Dispatchers.IO) { AppIconCache.icon(ctx, pkg) }
                    if (bytes == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "no icon for $pkg"))
                    else call.respondBytes(bytes, ContentType.Image.PNG)
                }

                post("/session/launch") {
                    val body = mapper.readValue<LaunchReq>(call.receive<String>())
                    val result = withContext(Dispatchers.IO) {
                        controller.launch(body.pkg, body.component)
                    }
                    if (!result.ok) MfaLog.w("Server", "launch failed pkg=${body.pkg}: ${result.message}")
                    call.respond(result)
                }

                get("/ui") {
                    val ui = withContext(Dispatchers.IO) { controller.readUi() }
                    if (ui == null) call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "ui unavailable (enable accessibility service)"))
                    else call.respondText(ui, ContentType.Application.Json)
                }

                get("/frame") {
                    val bytes = withContext(Dispatchers.IO) { controller.captureFrame() }
                    if (bytes == null) {
                        val sess = (controller as? com.managedfloating.core.controller.AppScreenController)?.vdSession != null
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "no frame", "session" to sess))
                    } else if (bytes.isEmpty()) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "empty frame"))
                    } else {
                        call.respondBytes(bytes, ContentType.Image.PNG)
                    }
                }

                post("/action/tap") {
                    val b = mapper.readValue<TapReq>(call.receive<String>())
                    val ok = withContext(Dispatchers.IO) { controller.tap(b.x, b.y) }
                    if (!ok) MfaLog.w("Server", "tap failed (${b.x},${b.y}) - session inactive?")
                    call.respond(mapOf("ok" to ok))
                }

                post("/action/swipe") {
                    val b = mapper.readValue<SwipeReq>(call.receive<String>())
                    val ok = withContext(Dispatchers.IO) {
                        controller.swipe(b.x1, b.y1, b.x2, b.y2, b.durMs ?: 300)
                    }
                    if (!ok) MfaLog.w("Server", "swipe failed - session inactive?")
                    call.respond(mapOf("ok" to ok))
                }

                // 任意按键注入（008-client MJPEG 降级模式的按键操作）。
                post("/action/key") {
                    val b = mapper.readValue<KeyReq>(call.receive<String>())
                    val ok = withContext(Dispatchers.IO) { controller.pressKey(b.code) }
                    if (!ok) MfaLog.w("Server", "key failed code=${b.code} - session inactive?")
                    call.respond(mapOf("ok" to ok))
                }

                // ---- MCP ----
                post("/mcp") {
                    val body = call.receiveText()
                    val sid = call.request.queryParameters["sessionId"]
                    val resp = mcp.handle(body)
                    if (sid != null) {
                        // SSE 传输：响应经该会话的 SSE 通道推回，POST 立即 202
                        if (resp != null) sessions[sid]?.trySend(resp)
                        call.respond(HttpStatusCode.Accepted, "")
                    } else if (resp == null) {
                        call.respond(HttpStatusCode.Accepted, "")
                    } else {
                        call.respondText(resp, ContentType.Application.Json)
                    }
                }
                get("/mcp/sse") {
                    val sid = java.util.UUID.randomUUID().toString()
                    val ch = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                    sessions[sid] = ch
                    try {
                        call.respondTextWriter(ContentType.Text.EventStream) {
                            write("event: endpoint\ndata: /mcp?sessionId=" + sid + "\n\n")
                            flush()
                            for (msg in ch) {
                                write("event: message\ndata: " + msg + "\n\n")
                                flush()
                            }
                        }
                    } finally {
                        sessions.remove(sid)
                    }
                }

                // ---- MJPEG 视频流 ----
                get("/stream") {
                    if (streamClients.get() >= MAX_STREAM_CLIENTS) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "too many stream clients"))
                        return@get
                    }
                    streamClients.incrementAndGet()
                    try {
                        call.respondOutputStream(
                            ContentType.parse("multipart/x-mixed-replace; boundary=frame"),
                        ) {
                            var last = 0L
                            while (true) {
                                val bytes = runCatching { controller.captureFrame() }.getOrNull()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    write(("--frame\r\nContent-Type: image/png\r\nContent-Length: " + bytes.size + "\r\n\r\n").toByteArray())
                                    write(bytes)
                                    write("\r\n".toByteArray())
                                    flush()
                                    last = System.currentTimeMillis()
                                } else if (System.currentTimeMillis() - last > 60_000) {
                                    break
                                }
                                try { Thread.sleep(200) } catch (e: InterruptedException) { break }
                            }
                        }
                    } finally {
                        streamClients.decrementAndGet()
                    }
                }

                // ---- WebRTC：控制台页面 + 信令（007-webrtc）----
                get("/webrtc") {
                    val w = webrtc
                    if (w == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "webrtc disabled (no app context)"))
                        return@get
                    }
                    call.respondText(WebRtcConsole.PAGE, ContentType.Text.Html)
                }

                // 008-client：播控客户端页面（单文件，token 经 query）。
                get("/client") {
                    call.respondText(ClientPage.HTML, ContentType.Text.Html)
                }
                webSocket("/webrtc/signaling") {
                    val w = webrtc
                    if (w == null) {
                        close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "webrtc disabled"))
                        return@webSocket
                    }
                    WebRtcWire.handle(this, w, controller, authToken)
                }

                // 自动化服务任务 CRUD 与执行
                if (taskApi != null) {
                    get("/tasks") { call.respondText(taskApi.listJson(), ContentType.Application.Json) }
                    post("/tasks") {
                        val ok = taskApi.upsert(call.receive<String>())
                        call.respond(mapOf("ok" to ok))
                    }
                    delete("/tasks/{id}") {
                        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no id"))
                        call.respond(mapOf("ok" to taskApi.delete(id)))
                    }
                    post("/tasks/{id}/run") {
                        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no id"))
                        call.respond(mapOf("ok" to taskApi.run(id)))
                    }
                    post("/tasks/{id}/stop") {
                        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no id"))
                        call.respond(mapOf("ok" to taskApi.stop(id)))
                    }
                }

                // 仅调试：设置 token（写入后需重启 App 生效）。
                post("/debug/set-token") {
                    val b = mapper.readValue<TokenReq>(call.receive<String>())
                    appContext?.let { ServerConfig(it).token = b.token }
                    call.respond(mapOf("ok" to true, "note" to "restart app to apply"))
                }

                // 仅调试：初始化 VdSession（连 adbd + 拉起助手），之后 /session/launch /frame /action 真正生效。
                post("/debug/session/start") {
                    val b = mapper.readValue<SessionStartReq>(call.receive<String>())
                    val hs = shell
                    val ctl = controller
                    val ctx = appContext
                    if (hs == null || ctx == null || ctl !is com.managedfloating.core.controller.AppScreenController) {
                        call.respond(mapOf("ok" to false, "error" to "no shell/controller"))
                        return@post
                    }
                    val session = withContext(Dispatchers.IO) {
                        com.managedfloating.core.controller.VdSessionImpl(
                            context = ctx,
                            shell = hs,
                            connectPort = b.connectPort,
                            width = b.width ?: 720,
                            height = b.height ?: 1560,
                            dpi = b.dpi ?: 400,
                        )
                    }
                    val ok = withContext(Dispatchers.IO) { session.start() }
                    if (ok) {
                        ctl.vdSession = session
                        MfaLog.i("Server", "debug session started port=${b.connectPort}")
                    } else {
                        MfaLog.e("Server", "debug session failed: ${hs.lastError}")
                    }
                    call.respond(mapOf("ok" to ok, "error" to hs.lastError?.toString()))
                }

                // 错误恢复：清理当前（可能已失效的）会话与助手，便于随后重新 /debug/session/start。
                post("/debug/session/stop") {
                    val ctl = controller
                    if (ctl is com.managedfloating.core.controller.AppScreenController) ctl.vdSession = null
                    runCatching { shell?.stopHelper() }
                    MfaLog.w("Server", "session stopped via /debug/session/stop")
                    call.respond(mapOf("ok" to true))
                }

                // 仅调试：免 root 连接 + 执行一条 shell 命令（配对已完成时用）。
                post("/debug/connect") {
                    val b = mapper.readValue<ConnectReq>(call.receive<String>())
                    val hs = shell
                    if (hs == null) {
                        call.respond(mapOf("connected" to false, "error" to "no shell"))
                        return@post
                    }
                    val connected = withContext(Dispatchers.IO) { hs.connect(b.connectPort) }
                    val output = if (connected) {
                        withContext(Dispatchers.IO) {
                            when {
                                b.hold == true -> hs.startHelper(b.command ?: "id")
                                b.pty == true -> hs.runPty(b.command ?: "id")
                                else -> hs.run(b.command ?: "id")
                            }
                        }
                    } else null
                    call.respond(
                        mapOf(
                            "connected" to connected,
                            "output" to output,
                            "error" to hs.lastError?.toString(),
                        ),
                    )
                }

                // 仅调试：免 root 配对 + 连接 + 执行一条 shell 命令，返回结果。
                post("/debug/pair") {
                    val b = mapper.readValue<PairReq>(call.receive<String>())
                    val hs = shell
                    if (hs == null) {
                        call.respond(mapOf("paired" to false, "connected" to false, "error" to "no shell"))
                        return@post
                    }
                    val paired = withContext(Dispatchers.IO) { hs.pair(b.host, b.pairPort, b.code) }
                    val connected = if (paired) withContext(Dispatchers.IO) { hs.connect(b.connectPort) } else false
                    val output = if (connected) {
                        withContext(Dispatchers.IO) { hs.run(b.command ?: "id") }
                    } else null
                    call.respond(
                        mapOf(
                            "paired" to paired,
                            "connected" to connected,
                            "output" to output,
                            "error" to hs.lastError?.toString(),
                        ),
                    )
                }
            }
        }
        // 独立线程 run：start(wait=true) 阻塞该线程，避免 CIO acceptJob 随调用线程协程被取消（Android 常见问题）。
        Thread {
            try {
                s.start(wait = true)
            } catch (t: Throwable) {
                // 绑定失败等不应崩坏宿主进程；记日志
                android.util.Log.e("ManagedServer", "server start failed", t)
                MfaLog.e("Server", "start failed", t)
            }
        }.apply {
            isDaemon = true
            name = "mfa-ktor"
        }.start()
        running = s
    }

    fun stop() {
        running?.stop(1000)
        running = null
    }

    @Volatile
    private var running: io.ktor.server.engine.ApplicationEngine? = null

    companion object {
        /** MJPEG 同时在线客户端上限。 */
        const val MAX_STREAM_CLIENTS = 8
    }

    data class LaunchReq(val pkg: String, val component: String? = null)
    data class TapReq(val x: Int, val y: Int)
    data class SwipeReq(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durMs: Int? = null)
    data class KeyReq(val code: Int)
    data class PairReq(
        val host: String,
        val pairPort: Int,
        val code: String,
        val connectPort: Int? = null,
        val command: String? = null,
    )
    data class ConnectReq(
        val connectPort: Int? = null,
        val command: String? = null,
        val pty: Boolean? = null,
        val hold: Boolean? = null,
    )
    data class TokenReq(val token: String)
    data class SessionStartReq(
        val connectPort: Int? = null,
        val width: Int? = null,
        val height: Int? = null,
        val dpi: Int? = null,
    )
}
