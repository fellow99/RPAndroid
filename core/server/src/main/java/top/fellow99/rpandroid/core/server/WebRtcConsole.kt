package top.fellow99.rpandroid.core.server

/**
 * [WebRtcConsole] —— 浏览器控制台页面（`GET /webrtc`）。
 *
 * 页面自通过查询参数携带 token（`/webrtc?token=<token>`），WS 信令复用同一 token（query）。
 * 功能：RTCPeerConnection 收 H.264 视频 + DataChannel("input") 回传 tap/swipe/key（归一化坐标）。
 * 注意：示例与占位符；真实 token/地址由使用者输入，未落盘。
 */
object WebRtcConsole {

    val PAGE: String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>RP自动化 · WebRTC 控制台</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; font: 14px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
         background:#0f1115; color:#e8eaed; }
  header { display:flex; align-items:center; gap:12px; padding:10px 16px; background:#1a1d24;
           border-bottom:1px solid #2a2e39; position:sticky; top:0; z-index:10; }
  header h1 { font-size:15px; margin:0; font-weight:600; flex:1; }
  #status { font-size:12px; color:#9aa0a6; }
  .dot { width:9px; height:9px; border-radius:50%; background:#5f6368; display:inline-block; }
  .dot.on { background:#34a853; }
  .dot.err { background:#ea4335; }
  main { padding:12px; }
  .wrap { position:relative; width:100%; max-width:640px; margin:0 auto; background:#000;
          border:1px solid #2a2e39; border-radius:10px; overflow:hidden; touch-action:none; }
  video { display:block; width:100%; height:auto; aspect-ratio:auto; max-height:80vh;
          object-fit:contain; cursor:crosshair; }
  .hint { position:absolute; left:10px; bottom:10px; font-size:11px; color:#fff;
          background:rgba(0,0,0,.55); padding:4px 8px; border-radius:6px; pointer-events:none; }
  #msg { padding:8px; font-size:12px; color:#f1c40f; min-height:18px; text-align:center; }
  .url { font-size:11px; color:#9aa0a6; padding:8px 16px; }
</style>
</head>
<body>
<header>
  <span class="dot" id="dot"></span>
  <h1>WebRTC 控制台</h1>
  <span id="status">connecting…</span>
</header>
<main>
  <div id="msg"></div>
  <div class="wrap">
    <video id="video" autoplay playsinline muted></video>
    <div class="hint">点击/拖动操作被托管 App；Backspace=返回；Enter=确认</div>
  </div>
  <div class="url" id="urlHint"></div>
</main>
<script>
"use strict";
(function () {
  var params = new URLSearchParams(location.search);
  var token = params.get("token") || "";
  var proto = location.protocol === "https:" ? "wss://" : "ws://";
  var wsUrl = proto + location.host + "/webrtc/signaling?token=" + encodeURIComponent(token);

  var video = document.getElementById("video");
  var dot = document.getElementById("dot");
  var statusEl = document.getElementById("status");
  var msgEl = document.getElementById("msg");
  var urlEl = document.getElementById("urlHint");

  function log(m) { console.log("[webrtc]", m); }
  function setStatus(text, cls) {
    statusEl.textContent = text;
    dot.className = "dot" + (cls ? " " + cls : "");
  }
  function setMsg(text) { msgEl.textContent = text; }

  var pc = null;
  var dc = null;
  var ws = null;
  var pending = [];   // ws 未开时的消息队列
  var started = false;

  function wsSend(o) {
    var s = JSON.stringify(o);
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(s); else pending.push(s);
  }

  function startSignaling() {
    pc = new RTCPeerConnection({ iceServers: [] });
    // App 是 offerer：服务端推送 sendonly 视频 + 创建 input DataChannel，浏览器收流即可。
    pc.ondatachannel = function (e) { dc = e.channel; wireData(dc); };
    pc.onicecandidate = function (e) {
      if (e.candidate) wsSend({ type: "ice", sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex, candidate: e.candidate.candidate });
    };
    pc.ontrack = function (e) {
      // 兼容流的关联缺失：streams 可能为空，兜底用 e.track 组装
      video.srcObject = (e.streams && e.streams[0]) ? e.streams[0] : new MediaStream([e.track]);
      setMsg("视频已连接");
    };
    pc.onconnectionstatechange = function () {
      var st = pc.connectionState;
      setStatus("RTC:" + st, st === "connected" ? "on" : (st === "failed" ? "err" : ""));
    };
  }

  function wireData(ch) {
    ch.onopen = function () { setMsg("输入通道已连接"); };
    ch.onerror = function () { setMsg("输入通道错误"); };
  }

  function sendTap(nx, ny) { if (dc && dc.readyState === "open") dc.send(JSON.stringify({ t: "tap", nx: nx, ny: ny })); }
  function sendSwipe(x1, y1, x2, y2, dur) { if (dc && dc.readyState === "open") dc.send(JSON.stringify({ t: "swipe", nx1: x1, ny1: y1, nx2: x2, ny2: y2, durMs: dur })); }
  function sendKey(c) { if (dc && dc.readyState === "open") dc.send(JSON.stringify({ t: "key", code: c })); }

  // letterbox 归一化坐标
  function norm(e) {
    var r = video.getBoundingClientRect();
    var vw = video.videoWidth, vh = video.videoHeight;
    if (!vw || !vh) return null;
    var scale = Math.min(r.width / vw, r.height / vh);
    var dw = vw * scale, dh = vh * scale;
    var ox = (r.width - dw) / 2, oy = (r.height - dh) / 2;
    var x = e.clientX - r.left, y = e.clientY - r.top;
    var nx = (x - ox) / dw, ny = (y - oy) / dh;
    if (nx < 0 || ny < 0 || nx > 1 || ny > 1) return null;
    return { nx: nx, ny: ny };
  }

  var down = null;
  video.addEventListener("pointerdown", function (e) {
    e.preventDefault();
    var p = norm(e); if (!p) return;
    down = p;
    try { video.setPointerCapture(e.pointerId); } catch (err) {}
  });
  video.addEventListener("pointermove", function (e) {
    if (!down) return;
    var p = norm(e); if (!p) return;
    var dx = p.nx - down.nx, dy = p.ny - down.ny;
    if (dx * dx + dy * dy > 0.003) {
      sendSwipe(down.nx, down.ny, p.nx, p.ny, 120);
      down = null;  // 拖拽后仍允许继续连滑
    }
  });
  video.addEventListener("pointerup", function (e) {
    var p = norm(e);
    if (down && p) { sendTap(p.nx, p.ny); }
    down = null;
  });
  video.addEventListener("pointercancel", function () { down = null; });
  window.addEventListener("blur", function () { down = null; });

  document.addEventListener("keydown", function (e) {
    if (e.code === "Backspace") { e.preventDefault(); sendKey(4); }        // KEYCODE_BACK
    else if (e.code === "Enter") { e.preventDefault(); sendKey(66); }       // KEYCODE_ENTER
    else if (e.code === "Space") { e.preventDefault(); sendKey(62); }       // KEYCODE_SPACE
    else if (e.code === "Home") { e.preventDefault(); sendKey(3); }         // KEYCODE_HOME
  });

  // 信令
  ws = new WebSocket(wsUrl);
  ws.onopen = function () {
    setStatus("信令已连接", "on");
    while (pending.length) ws.send(pending.shift());
    startSignaling();
  };
  ws.onmessage = function (ev) {
    var d = null;
    try { d = JSON.parse(ev.data); } catch (e) { return; }
    if (d.type === "offer") {
      // 浏览器 answerer：收到服务端 offer → answer
      pc.setRemoteDescription({ type: "offer", sdp: d.sdp }).then(function () {
        return pc.createAnswer();
      }).then(function (ans) {
        return pc.setLocalDescription(ans);
      }).then(function () {
        wsSend({ type: "answer", sdp: pc.localDescription.sdp });
      }).catch(function (e) { setMsg("answer 失败: " + e.message); });
    } else if (d.type === "ice") {
      pc.addIceCandidate({ sdpMid: d.sdpMid, sdpMLineIndex: d.sdpMLineIndex, candidate: d.candidate }).catch(function (e) { log("ice err " + e); });
    } else if (d.type === "state") {
      setStatus(d.state, d.state.indexOf("error") === 0 ? "err" : "");
    }
  };
  ws.onclose = function () { setStatus("信令关闭", "err"); };
  ws.onerror = function () { setStatus("信令错误", "err"); };

  urlEl.textContent = location.protocol + "//" + location.host + "/stream?token=" + encodeURIComponent(token) + "  (MJPEG 降级通道，打开新标签页)";
  setMsg("等待会话/信令…");
})();
</script>
</body>
</html>
""".trimIndent()
}
