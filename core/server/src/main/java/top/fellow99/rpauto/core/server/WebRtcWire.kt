package top.fellow99.rpauto.core.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import top.fellow99.rpauto.core.controller.ScreenController
import top.fellow99.rpauto.core.webrtc.PeerEvents
import top.fellow99.rpauto.core.webrtc.WebRtcPush
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * [WebRtcWire] —— 007-webrtc 的浏览器信令（WS）与反向输入映射（core:server）。
 *
 * 职责：
 * - `/webrtc/signaling`：WS 双向转发 offer/answer/ICE；每个连接一个 [WebRtcPush.PeerSession]。
 * - DataChannel 输入（归一化坐标 JSON）→ 按 FrameSource 尺寸放大 → [ScreenController]
 *   （C-1 写单入口，与 REST/MCP 一致）；无会话则丢弃。
 *
 * 安全：WS 复用现有 token（查询参数 `?token=`）；页面路径同样带 token。
 * 文档承诺：示例一律占位符，不落真实 IP/token。
 */
object WebRtcWire {

    private val mapper = jacksonObjectMapper()

    private fun json(obj: Map<String, Any?>): String = mapper.writeValueAsString(obj)

    /** 组装 WS 会话：绑定 peer、双向转发信令；结束后释放 peer。 */
    suspend fun handle(
        session: DefaultWebSocketServerSession,
        push: WebRtcPush,
        controller: ScreenController,
        authToken: String,
    ) {
        val reqToken = session.call.request.queryParameters["token"]
        if (authToken.isNotEmpty() && reqToken != authToken) {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return
        }

        val peer = push.startPeer()
        if (peer == null) {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no peer (cap reached / not ready)"))
            return
        }

        // libwebrtc 回调线程上发 WS：用一个 scope 派发（send 为挂起函数）
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            peer.events = object : PeerEvents {
                override fun onLocalOffer(sdp: String) {
                    scope.launch { runCatching { session.send(Frame.Text(json(mapOf("type" to "offer", "sdp" to sdp)))) } }
                }
                override fun onLocalIce(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    scope.launch {
                        runCatching {
                            session.send(Frame.Text(json(mapOf("type" to "ice", "sdpMid" to sdpMid, "sdpMLineIndex" to sdpMLineIndex, "candidate" to candidate))))
                        }
                    }
                }
                override fun onState(state: String) {
                    scope.launch { runCatching { session.send(Frame.Text(json(mapOf("type" to "state", "state" to state)))) } }
                }
            }
            peer.events?.onState("created")
            // App 为 offerer：创建输入 DataChannel 并推送 offer 给浏览器
            peer.startNegotiation()

            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> if (!dispatchClientMessage(peer, frame.readText(), session, scope)) break
                    is Frame.Close -> break
                    else -> {}
                }
            }
        } finally {
            scope.cancel()
            push.releasePeer(peer)
            runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "closed")) }
        }
    }

    private fun dispatchClientMessage(
        peer: WebRtcPush.PeerSession,
        text: String,
        session: DefaultWebSocketServerSession,
        scope: CoroutineScope,
    ): Boolean {
        val node: JsonNode = runCatching { mapper.readTree(text) }.getOrNull() ?: return true
        return when (node.get("type")?.asText()) {
            "answer" -> {
                node.get("sdp")?.asText()?.let { peer.handleAnswer(it) }
                true
            }
            "ice" -> {
                peer.handleIce(
                    node.get("sdpMid")?.asText(),
                    node.get("sdpMLineIndex")?.asInt() ?: 0,
                    node.get("candidate")?.asText() ?: "",
                )
                true
            }
            "ping" -> {
                scope.launch { runCatching { session.send(Frame.Text("{\"type\":\"pong\"}")) } }
                true
            }
            else -> true
        }
    }

    /** 输入文本 → ScreenController（归一化坐标 → 虚拟屏像素）。由 push.onInput 安装。 */
    fun handleInput(controller: ScreenController, text: String) {
        if (text.isBlank()) return
        val node: JsonNode = runCatching { mapper.readTree(text) }.getOrNull() ?: return
        val fs = controller.frameSource() ?: return
        val w = fs.width
        val h = fs.height
        if (w <= 0 || h <= 0) return
        try {
            when (node.get("t")?.asText()) {
                "tap" -> controller.tap(
                    px(node.get("nx")?.asDouble(), w - 1),
                    px(node.get("ny")?.asDouble(), h - 1),
                )
                "swipe" -> controller.swipe(
                    px(node.get("nx1")?.asDouble(), w - 1),
                    px(node.get("ny1")?.asDouble(), h - 1),
                    px(node.get("nx2")?.asDouble(), w - 1),
                    px(node.get("ny2")?.asDouble(), h - 1),
                    (node.get("durMs")?.asInt() ?: 300),
                )
                "key" -> controller.pressKey(node.get("code")?.asInt() ?: 0)
                else -> {}
            }
        } catch (t: Throwable) {
            MfaLog.w("WebRtcWire", "input mapped failed: ${t.message}")
        }
    }

    /** 归一化 0..1 → 虚拟屏像素（钳制在 [0, maxInclusive]，避免注入到 w/h 越界一像素）。 */
    private fun px(v: Double?, maxInclusive: Int): Int =
        ((v ?: 0.0).coerceIn(0.0, 1.0) * maxInclusive).toInt().coerceIn(0, maxInclusive)
}
