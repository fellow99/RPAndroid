package top.fellow99.rpandroid.core.webrtc

import android.content.Context
import top.fellow99.rpandroid.core.controller.ScreenController
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
 * H.264 硬件编码后经 PeerConnection 推给**浏览器**客户端；DataChannel("input") 承载浏览器输入
 * （归一化坐标），由 server 层解析并收敛到 [ScreenController]（C-1 单入口）。
 *
 * 拓扑：**App 为 offerer（推流方）**——本端 addTrack 后 createOffer（video=sendonly，浏览器只需收），
 * 并主动创建输入 DataChannel；浏览器 answer。这绕开了 2021 版 libwebrtc 作为 answerer 应答
 * recvonly 远端 m 行时 “Failed to set remote video description send parameters” 的兼容问题。
 *
 * 约束：仅局域网 host ICE（无 STUN/TURN）；并发 peer 上限 [MAX_PEERS]（2）。
 * JSON 解析留在 server 层，本模块不依赖 Jackson。
 */
class WebRtcPush(
    private val context: Context,
    private val controller: ScreenController,
) {

    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var source: VideoSource? = null
    @Volatile private var track: VideoTrack? = null
    private var eglBase: org.webrtc.EglBase? = null

    private val pusher = FramePusher(
        frameSource = { controller.frameSource() },
        sink = { frame ->
            val s = source
            if (s != null) {
                try {
                    // onFrameCaptured 不接管所有权：本实现对(pusher toI420)自建缓冲区必须显式释放，
                    // 否则每帧 1.7MB JavaI420Buffer 泄漏（实测 ~25MB/s，进而触发系统 mem-pressure 回收）。
                    s.getCapturerObserver().onFrameCaptured(frame)
                } finally {
                    frame.release()
                }
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
        // 仅在确有在线 peer 时才跑推流（避免 0 peer 时持续编码占 CPU）
        pusher.start()
        return session
    }

    private fun ensureInitialized(): Boolean {
        return synchronized(this) {
            if (factory != null) return@synchronized true
            try {
                // PeerConnectionFactory.initialize 全局仅能一次；stop() 后重建 factory 不能重复 initialize
                if (!globalInitDone) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions()
                    )
                    globalInitDone = true
                }
                // 显式建 EglBase + 编解码工厂：builder 默认的 RootEglBase 在部分 ROM 上 EGL 上下文创建失败，
                // 会得到“Failed to set local/remote video description … send/recv parameters”的空 codec 协商。
                val egl = org.webrtc.EglBase.create(null, org.webrtc.EglBase.CONFIG_RGBA)
                val encoderFactory = org.webrtc.DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
                val decoderFactory = org.webrtc.DefaultVideoDecoderFactory(egl.eglBaseContext)
                android.util.Log.i(TAG, "enc codecs=" + encoderFactory.supportedCodecs.joinToString { it.name })
                android.util.Log.i(TAG, "dec codecs=" + decoderFactory.supportedCodecs.joinToString { it.name })
                val f = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory()
                val s = f.createVideoSource(false)
                val t = f.createVideoTrack(TRACK_ID, s)
                eglBase = egl
                factory = f
                source = s
                track = t
                android.util.Log.i(TAG, "initialized peer connection factory")
                true
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "webrtc init failed", t)
                false
            }
        }
    }

    fun releasePeer(session: PeerSession) {
        if (peers.remove(session)) {
            session.close()
            if (peers.isEmpty()) {
                pusher.stop()
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
        runCatching { eglBase?.release() }
        track = null
        source = null
        factory = null
        eglBase = null
    }

    fun activePeers(): Int = peers.size

    /** 每个浏览器客户端一条会话（一条 WS/PeerConnection）。 */
    class PeerSession(private val push: WebRtcPush) {
        /** 信令回调（由 server 的 WS 层注入）。 */
        @Volatile var events: PeerEvents? = null
        private val lock = Any()
        private var pc: PeerConnection? = null
        private var dc: DataChannel? = null

        /** 创建 PeerConnection 并挂发送视频轨（offerer 侧直接 addTrack，offer 为 sendonly）。 */
        fun create(): Boolean = synchronized(lock) {
            val f = push.factory ?: return false
            val cfg = PeerConnection.RTCConfiguration(emptyList())
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            val p = f.createPeerConnection(cfg, observer()) ?: return false
            val tr = push.track
            if (tr != null) {
                runCatching { p.addTrack(tr, listOf(STREAM_ID)) }  // 带 stream id → offer 含 a=msid，浏览器 ontrack 有流
                // 只发送（接收留给对端）；避免 offer 里生成 sendrecv/recv 参数（无解码器时协商失败）
                runCatching {
                    p.getTransceivers().firstOrNull {
                        it.mediaType == org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                    }?.setDirection(org.webrtc.RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                }
            }
            pc = p
            true
        }

        /** 主动发起协商（offerer）：创建输入 DataChannel + createOffer → setLocal → 回推 offer。 */
        fun startNegotiation() {
            val p = pc ?: return
            val config = DataChannel.Init().apply { ordered = true }
            val channel = p.createDataChannel(DATA_CHANNEL_INPUT, config)
            channel.registerObserver(object : DataChannel.Observer {
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
            dc = channel
            p.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    val pp = pc
                    if (pp == null || desc == null) return
                    pp.setLocalDescription(SetLocalObserver, desc)
                }
                override fun onCreateFailure(error: String?) { events?.onState("error:offer:$error") }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }

        private val SetLocalObserver = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) { events?.onState("error:setLocal:$error") }
            override fun onSetSuccess() {
                pc?.localDescription?.let { events?.onLocalOffer(it.description) }
            }
        }

        /** 浏览器 SDP answer。 */
        fun handleAnswer(sdp: String) {
            pc?.setRemoteDescription(
                object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) { events?.onState("error:setRemote:$error") }
                    override fun onSetSuccess() { events?.onState("connected") }
                },
                SessionDescription(SessionDescription.Type.ANSWER, sdp),
            )
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
                dc = null
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
                // offerer 侧通常不发生（通道由本端创建）；兜底处理远端主动建通道的情况
                dataChannel?.let { dcRemote ->
                    dcRemote.registerObserver(object : DataChannel.Observer {
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
        private const val STREAM_ID = "mfa-stream-0"
        private const val DATA_CHANNEL_INPUT = "input"
        const val MAX_PEERS = 2

        /** PeerConnectionFactory.initialize 全局仅一次（进程级）。 */
        @Volatile private var globalInitDone = false
    }
}

/** WebRtcPush.PeerSession 的信令回调（server WS 层实现）。 */
interface PeerEvents {
    fun onLocalOffer(sdp: String)
    fun onLocalIce(sdpMid: String, sdpMLineIndex: Int, candidate: String)
    fun onState(state: String)
}
