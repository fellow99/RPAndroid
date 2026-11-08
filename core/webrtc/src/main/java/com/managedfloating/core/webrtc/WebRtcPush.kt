package com.managedfloating.core.webrtc

import android.content.Context
import com.managedfloating.core.controller.ScreenController
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [WebRtcPush] —— 007-webrtc 推流 + 反向输入（core:webrtc）。
 *
 * 共享一个 [VideoSource]/[VideoTrack]，[FramePusher] 把 App 进程 FrameHub 的帧转为 I420 喂入，
 * H.264 硬件编码后经 PeerConnection 推给**浏览器**客户端；DataChannel 承载浏览器输入
 * （归一化坐标），由 server 层解析并收敛到 [ScreenController]（C-1 单入口）。
 *
 * 设计约束：
 * - 仅局域网 host ICE（无 STUN/TURN，无公网）。
 * - 并发 peer 上限 [MAX_PEERS]（2），避免多路硬件编码器撑爆设备。
 * - 只有 org.webrtc 依赖 + core:controller；JSON 解析留在 server 层。
 */
class WebRtcPush(
    private val context: Context,
    private val controller: ScreenController,
) {

    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var source: VideoSource? = null
    @Volatile private var track: VideoTrack? = null

    private val pusher = FramePusher(
        frameSource = { controller.frameSource() },
        sink = { frame ->
            val s = source
            if (s != null) {
                runCatching { s.getCapturerObserver().onFrameCaptured(frame) }
            } else {
                frame.getBuffer().release()
            }
        },
    )

    private val peers = CopyOnWriteArrayList<PeerSession>()

    /** 浏览器 DataChannel 来的输入文本（JSON），由 server 解析并映射到 ScreenController。 */
    @Volatile var onInput: ((String) -> Unit)? = null

    @Synchronized
    fun startPeer(): PeerSession? {
        if (factory == null && !ensureInitialized()) return null
        if (peers.size >= MAX_PEERS) {
            android.util.Log.w(TAG, "peer cap ($MAX_PEERS) reached")
            return null
        }
        val session = PeerSession(this)
        if (!session.create()) return null
        peers.add(session)
        return session
    }

    private fun ensureInitialized(): Boolean {
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val f = PeerConnectionFactory.builder().createPeerConnectionFactory()
            val s = f.createVideoSource(false)
            val t = f.createVideoTrack(TRACK_ID, s)
            factory = f
            source = s
            track = t
            pusher.start()
            android.util.Log.i(TAG, "initialized peer connection factory")
            true
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "webrtc init failed", t)
            false
        }
    }

    fun releasePeer(session: PeerSession) {
        if (peers.remove(session)) {
            session.close()
            if (peers.isEmpty()) {
                pusher.stop()
                // 保留 factory/source/track 供下个会话复用；整体释放由 stop() 负责。
            }
        }
    }

    /** 整体关闭（服务停止/会话销毁时调用）。 */
    @Synchronized
    fun stop() {
        for (s in peers.toList()) {
            s.close()
        }
        peers.clear()
        pusher.stop()
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { factory?.dispose() }
        track = null
        source = null
        factory = null
    }

    fun activePeers(): Int = peers.size

    /** 每个浏览器客户端一条会话（一条 WS/PeerConnection）。 */
    class PeerSession(private val push: WebRtcPush) {
        /** 信令回调（由 server 的 WS 层注入）。 */
        @Volatile var events: PeerEvents? = null
        private val lock = Any()
        private var pc: PeerConnection? = null

        fun create(): Boolean = synchronized(lock) {
            val f = push.factory ?: return false
            val cfg = PeerConnection.RTCConfiguration(emptyList())
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            val p = f.createPeerConnection(cfg, observer()) ?: return false
            push.track?.let { p.addTrack(it, emptyList()) }
            pc = p
            true
        }

        /** 浏览器 SDP offer → 本地 answer 回推。 */
        fun handleOffer(sdp: String) {
            val p = pc ?: return
            p.setRemoteDescription(
                object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {
                        events?.onState("error:setRemote:$error")
                    }
                    override fun onSetSuccess() {
                        p.createAnswer(AnswerObserver, MediaConstraints())
                    }
                },
                SessionDescription(SessionDescription.Type.OFFER, sdp),
            )
        }

        private val AnswerObserver = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                val p = pc
                if (p == null || desc == null) return
                p.setLocalDescription(
                    object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                        override fun onSetFailure(error: String?) {
                            events?.onState("error:setLocal:$error")
                        }
                        override fun onSetSuccess() {
                            p.localDescription?.let { events?.onLocalAnswer(it.description) }
                        }
                    },
                    desc,
                )
            }
            override fun onCreateFailure(error: String?) { events?.onState("error:answer:$error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }

        /** 浏览器 ICE candidate。 */
        fun handleIce(mid: String?, mline: Int, candidate: String) {
            runCatching { pc?.addIceCandidate(IceCandidate(mid, mline, candidate)) }
                .onFailure { android.util.Log.w(TAG, "addIceCandidate failed", it) }
        }

        fun state(): String? = pc?.connectionState()?.let { "pc:" + it }

        fun close() {
            synchronized(lock) {
                runCatching { pc?.close() }
                pc = null
            }
        }

        private fun observer() = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                events?.onState("ice:" + state)
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    events?.onLocalIce(it.sdpMid ?: "", it.sdpMLineIndex, it.sdp)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(mediaStream: MediaStream?) {}
            override fun onRemoveStream(mediaStream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {
                dataChannel?.let { dc ->
                    dc.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(previousAmount: Long) {}
                        override fun onStateChange() {}
                        override fun onMessage(buffer: DataChannel.Buffer) {
                            if (buffer.binary) return
                            val data = buffer.data
                            val arr = ByteArray(data.remaining())
                            data.get(arr)
                            push.onInput?.invoke(String(arr, Charsets.UTF_8))
                        }
                    })
                }
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        }
    }

    companion object {
        private const val TAG = "WebRtcPush"
        private const val TRACK_ID = "mfa-video-0"
        const val MAX_PEERS = 2
    }
}

/** WebRtcPush.PeerSession 的信令回调（server WS 层实现）。 */
interface PeerEvents {
    fun onLocalAnswer(sdp: String)
    fun onLocalIce(sdpMid: String, sdpMLineIndex: Int, candidate: String)
    fun onState(state: String)
    fun onInput(text: String)
}
