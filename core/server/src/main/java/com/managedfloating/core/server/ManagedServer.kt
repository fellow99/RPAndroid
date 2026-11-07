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
import io.ktor.server.routing.*
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
) {

    private val mapper = jacksonObjectMapper()

    fun start() {
        if (running != null) return
        val s = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) { jackson() }
            install(createApplicationPlugin("TokenAuth") {
                onCall { call ->
                    val path = call.request.local.uri
                    if (authToken.isNotEmpty() && path != "/health" && !path.startsWith("/debug/")) {
                        val got = call.request.headers[ServerConfig.HEADER]
                        if (got != authToken) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                        }
                    }
                }
            })
            routing {
                get("/health") { call.respond(mapOf("ok" to true, "service" to "ManagedFloatingApp")) }

                get("/apps") {
                    val apps = withContext(Dispatchers.IO) { controller.listApps() }
                    call.respond(apps)
                }

                post("/session/launch") {
                    val body = mapper.readValue<LaunchReq>(call.receive<String>())
                    val result = withContext(Dispatchers.IO) {
                        controller.launch(body.pkg, body.component)
                    }
                    call.respond(result)
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
                    call.respond(mapOf("ok" to ok))
                }

                post("/action/swipe") {
                    val b = mapper.readValue<SwipeReq>(call.receive<String>())
                    val ok = withContext(Dispatchers.IO) {
                        controller.swipe(b.x1, b.y1, b.x2, b.y2, b.durMs ?: 300)
                    }
                    call.respond(mapOf("ok" to ok))
                }

                // 脚本任务 CRUD 与执行
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
                    if (ok) ctl.vdSession = session
                    call.respond(mapOf("ok" to ok, "error" to hs.lastError?.toString()))
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

    data class LaunchReq(val pkg: String, val component: String? = null)
    data class TapReq(val x: Int, val y: Int)
    data class SwipeReq(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durMs: Int? = null)
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
