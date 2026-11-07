package com.managedfloating.core.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.managedfloating.core.controller.ScreenController
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
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ManagedServer] —— 对外 REST 接口（架构 D）。
 *
 * 复用 [ScreenController] 作为唯一操作入口。局域网直连 / USB(adb reverse) 接入。
 */
class ManagedServer(
    private val controller: ScreenController,
    private val port: Int,
) {

    private val mapper = jacksonObjectMapper()

    fun start() {
        if (running != null) return
        val s = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) { jackson() }
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
                    if (bytes == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "no frame"))
                    else call.respondBytes(bytes, ContentType.Image.PNG)
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
}
