package com.managedfloating.core.server

/**
 * [ClientPage] —— 008-client 播控客户端页面（`GET /client?token=<token>`）。
 *
 * 单文件自包含 HTML（无外部 CDN），移动端优先。功能：
 * - 引导：/health → 错误浮窗 / token 强制浮窗（遮挡全屏）→ /status 状态栏。
 * - 主回显：先 /frame 探会话（无图→「暂无会话」）；有帧→WebRTC(WS 信令 + DataChannel 输入)；
 *   WebRTC 失败/超时 → MJPEG(`/stream`) 降级（REST 操作同步）。
 * - 菜单：播控(关抽屉) ｜ 应用(图标抽屉+切换会话) ｜ 自动化(列表/互斥执行/停止/编辑) ｜ 日志(抽屉)。
 * - 鉴权：token 取地址栏?token= 或 localStorage；API 用 X-Auth-Token，img/WS 用查询参数。
 *
 * 注意：本页面不硬编码任何真实 IP/token/账号/密码/凭据（C-12）；示例一律占位符。
 * Kotlin 原始字符串内不能出现 `$`, 故全 JS 用字符串拼接（与 WebRtcConsole 同风格）。
 */
object ClientPage {

    val HTML: String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<link rel="icon" href="data:,"/>
<title>ManagedFloatingApp 客户端</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; font:14px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
         background:#0f1115; color:#e8eaed; height:100vh; overflow:hidden; }
  header { display:flex; align-items:center; gap:10px; padding:8px 12px; background:#1a1d24;
           border-bottom:1px solid #2a2e39; position:fixed; top:0; left:0; right:0; z-index:70; }
  .brand { display:flex; align-items:center; gap:8px; font-weight:600; font-size:15px; white-space:nowrap; }
  .dot { width:9px; height:9px; border-radius:50%; background:#5f6368; display:inline-block; }
  .dot.on { background:#34a853; }
  .dot.err { background:#ea4335; }
  .menu { display:flex; gap:4px; margin:0 auto; }
  .tab { background:transparent; border:none; color:#9aa0a6; font-size:14px; padding:6px 12px;
         border-radius:8px; cursor:pointer; }
  .tab.active { background:#2f3542; color:#fff; }
  .status { font-size:11px; color:#9aa0a6; text-align:right; max-width:38%; white-space:nowrap;
            overflow:hidden; text-overflow:ellipsis; }
  main { position:fixed; top:48px; left:0; right:0; bottom:0; }
  .stage { position:relative; width:100%; height:100%; background:#000; display:flex;
           align-items:center; justify-content:center; }
  #video, #mjpeg { max-width:100%; max-height:100%; object-fit:contain; cursor:crosshair; touch-action:none; }
  #mock { display:none; }
  .badge { position:absolute; top:10px; left:12px; font-size:11px; color:#fff; background:rgba(0,0,0,.55);
           padding:3px 8px; border-radius:6px; }
  .hint { position:absolute; left:0; right:0; top:45%; text-align:center; color:#9aa0a6; font-size:15px;
          pointer-events:none; }
  .toast { position:fixed; top:58px; left:50%; transform:translateX(-50%); z-index:60;
           background:#2f3542; color:#fff; padding:8px 14px; border-radius:8px; font-size:13px;
           box-shadow:0 2px 8px rgba(0,0,0,.4); max-width:80%; }
  .backdrop { position:fixed; inset:0; background:rgba(0,0,0,.45); z-index:30; }
  .drawer { position:fixed; top:48px; bottom:0; right:0; width:50vw; min-width:300px; max-width:460px;
            background:#15181e; border-left:1px solid #2a2e39; z-index:35; transform:translateX(105%);
            transition:transform .18s ease; display:flex; flex-direction:column; }
  .drawer.open { transform:translateX(0); }
  .drawer h3 { margin:0; padding:12px 14px; font-size:14px; border-bottom:1px solid #2a2e39; }
  .drawer .body { flex:1; overflow-y:auto; padding:10px 12px; }
  .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(84px,1fr)); gap:10px; }
  .tile { display:flex; flex-direction:column; align-items:center; gap:6px; padding:8px 4px; border-radius:10px;
          cursor:pointer; background:#1d212a; }
  .tile:hover { background:#2a2e39; }
  .tile-ico { width:40px; height:40px; border-radius:9px; object-fit:contain; background:#000; }
  .tile-name { font-size:11px; color:#cfd3dc; text-align:center; word-break:break-all; max-height:30px;
               overflow:hidden; }
  .arow { display:flex; align-items:center; gap:10px; padding:9px 8px; border-radius:10px;
          background:#1d212a; margin-bottom:8px; }
  .acol { flex:1; min-width:0; }
  .aname { font-size:13px; font-weight:600; }
  .asub, .alast { font-size:11px; color:#8f96a3; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .alast.err { color:#f28b82; }
  .actr { display:flex; align-items:center; gap:6px; }
  .btn { background:#2f3542; color:#fff; border:none; border-radius:8px; padding:6px 10px; font-size:12px;
         cursor:pointer; }
  .btn:hover { background:#3a4150; }
  .btn.sm { padding:4px 8px; font-size:11px; }
  .switch { position:relative; width:34px; height:19px; flex:none; }
  .switch input { opacity:0; width:0; height:0; }
  .switch span { position:absolute; inset:0; background:#3a4150; border-radius:19px; transition:.15s; }
  .switch span:before { content:""; position:absolute; width:15px; height:15px; left:2px; top:2px;
                        background:#fff; border-radius:50%; transition:.15s; }
  .switch input:checked + span { background:#34a853; }
  .switch input:checked + span:before { transform:translateX(15px); }
  .lrow { display:flex; gap:6px; font-family:monospace; font-size:11px; padding:3px 2px;
          border-bottom:1px solid #1e222b; }
  .ll { font-weight:700; width:16px; }
  .ll.e { color:#f28b82; } .ll.w { color:#fdd663; } .ll.i { color:#81c995; } .ll.d { color:#8ab4f8; }
  .lt { color:#6b7280; flex:none; } .ltag { color:#8ab4f8; flex:none; }
  .lmsg { color:#cfd3dc; word-break:break-all; }
  .blocker { position:fixed; inset:0; z-index:100; background:rgba(8,10,14,.88); display:flex;
             align-items:center; justify-content:center; }
  .box { width:min(340px, 86vw); background:#1d212a; border:1px solid #2a2e39; border-radius:14px;
         padding:22px; }
  .box h2 { margin:0 0 12px; font-size:16px; }
  .box p { margin:4px 0 12px; font-size:13px; color:#9aa0a6; word-break:break-all; }
  input[type=text], input[type=password], input[type=number], textarea, select {
    width:100%; background:#0f1115; border:1px solid #2a2e39; border-radius:8px; color:#e8eaed;
    padding:8px 10px; font-size:13px; margin:4px 0; }
  textarea { resize:vertical; min-height:120px; font-family:monospace; font-size:12px; }
  label { font-size:12px; color:#9aa0a6; display:block; margin-top:8px; }
  .err { color:#f28b82; font-size:12px; min-height:16px; margin:6px 0; }
  .box .row { display:flex; gap:8px; margin-top:10px; }
  .box .row .btn { flex:1; }
  .box .btn.primary { background:#1a73e8; }
  .modal-scroll { max-height:70vh; overflow-y:auto; }
  .footer { border-top:1px solid #2a2e39; padding:8px 14px; display:flex; gap:8px; align-items:center; }
  .footer select { width:auto; }
  .empty { color:#6b7280; text-align:center; padding:30px 0; }
</style>
</head>
<body>
<header>
  <div class="brand"><span class="dot" id="dot"></span><span>ManagedFloatingApp 客户端</span></div>
  <nav class="menu" id="menu">
    <button class="tab" data-tab="ctrl">播控</button>
    <button class="tab" data-tab="apps">应用</button>
    <button class="tab" data-tab="auto">自动化</button>
    <button class="tab" data-tab="logs">日志</button>
  </nav>
  <div class="status" id="statusRight">…</div>
</header>
<main>
  <div class="stage" id="stage">
    <video id="video" autoplay playsinline muted></video>
    <img id="mjpeg" alt=""/>
    <span class="badge" id="modeBadge"></span>
    <div class="hint" id="stageHint"></div>
  </div>
</main>
<div id="toast" class="toast" style="display:none"></div>

<div class="backdrop" id="backdrop" style="display:none"></div>

<aside class="drawer" id="drawer-apps">
  <h3>应用</h3>
  <div class="body"><div class="grid" id="appsList"></div></div>
  <div class="footer"><span style="color:#6b7280;font-size:11px">点击图标切换被托管应用</span></div>
</aside>

<aside class="drawer" id="drawer-auto">
  <h3>自动化</h3>
  <div class="body" id="autoList"></div>
  <div class="footer">
    <button class="btn" id="autoNew">＋ 新建</button>
    <button class="btn" id="autoRefresh">刷新</button>
    <span style="flex:1"></span>
    <span style="color:#6b7280;font-size:11px" id="autoRunHint">同一时刻仅一个执行</span>
  </div>
</aside>

<aside class="drawer" id="drawer-logs">
  <h3>日志</h3>
  <div class="body" id="logList"></div>
  <div class="footer">
    <select id="logLevel">
      <option value="">全部</option>
      <option value="E">E</option>
      <option value="W">W</option>
      <option value="I">I</option>
      <option value="D">D</option>
    </select>
    <button class="btn" id="logClear">清空</button>
  </div>
</aside>

<div class="blocker" id="tokenOverlay">
  <div class="box">
    <h2>需要访问 token</h2>
    <p>该服务已启用 token 鉴权，请先输入访问 token。</p>
    <input type="password" id="tokenInput" placeholder="请输入 token" autocomplete="off"/>
    <div class="err" id="tokenErr"></div>
    <div class="row">
      <button class="btn primary" id="tokenOk">进入</button>
      <button class="btn" id="tokenRefresh">刷新页面</button>
    </div>
  </div>
</div>

<div class="blocker" id="errorOverlay" style="display:none">
  <div class="box">
    <h2>服务异常</h2>
    <p id="errorMsg">服务不可达或返回异常。</p>
    <div class="row"><button class="btn primary" id="errorRefresh">刷新</button></div>
  </div>
</div>

<div class="blocker" id="editModal" style="display:none">
  <div class="box modal-scroll">
    <h2 id="editTitle">编辑自动化</h2>
    <label>名称</label>
    <input type="text" id="f_name"/>
    <label>目标 App（packageName，可选）</label>
    <input type="text" id="f_pkg" placeholder="com.android.settings"/>
    <label><input type="checkbox" id="f_enabled" style="width:auto"/> 启用</label>
    <label>执行方式</label>
    <select id="f_kind">
      <option value="IMMEDIATE">立即执行</option>
      <option value="INTERVAL">间隔执行</option>
      <option value="CRON">cron 定时</option>
    </select>
    <label>间隔毫秒（INTERVAL）</label>
    <input type="number" id="f_interval" placeholder="60000"/>
    <label>cron 表达式（CRON）</label>
    <input type="text" id="f_cron" placeholder="0 * * * * *"/>
    <label>自动化脚本（JS）</label>
    <textarea id="f_script"></textarea>
    <div class="err" id="editErr"></div>
    <div class="row">
      <button class="btn primary" id="editSave">保存</button>
      <button class="btn" id="editCancel">取消</button>
    </div>
  </div>
</div>

<script>
"use strict";
(function () {
  var params = new URLSearchParams(location.search);
  var token = params.get("token") || "";
  if (!token) { try { token = localStorage.getItem("mfa_token") || ""; } catch (e) { token = ""; } }
  var BASE = location.protocol + "//" + location.host;

  var status = null;
  var tasks = [];
  var appsMap = {};
  var openDrawer = null;
  var videoState = "idle";     // idle|none|connecting|rtc|mjpeg
  var pc = null, dc = null, ws = null, wsQueue = [];
  var rtcTimer = null;
  var runningServer = null;    // 服务端权威（/status.runningTaskId）
  var runningLocal = null;     // 本地乐观值（点击执行后立即生效）
  var lastServerRunning = null;
  var editing = null;
  var activeTab = "ctrl";
  var poll = {};

  function qf(id) { return document.getElementById(id); }
  function enc(s) { return encodeURIComponent(s); }
  function el(tag, cls, text) {
    var d = document.createElement(tag);
    if (cls) d.className = cls;
    if (text !== undefined && text !== null) d.textContent = text;
    return d;
  }
  function urlWithToken(path) {
    return path + (token ? (path.indexOf("?") >= 0 ? "&" : "?") + "token=" + enc(token) : "");
  }
  function api(path, opts) {
    opts = opts || {};
    var headers = {};
    if (opts.headers) { for (var k in opts.headers) headers[k] = opts.headers[k]; }
    if (token) headers["X-Auth-Token"] = token;
    var init = { method: opts.method || "GET", headers: headers };
    if (opts.body !== undefined) init.body = opts.body;
    return fetch(path, init);
  }
  function showToast(m) {
    var t = qf("toast"); t.textContent = m; t.style.display = "";
    clearTimeout(showToast._t);
    showToast._t = setTimeout(function () { t.style.display = "none"; }, 3200);
  }
  function hide(id) { qf(id).style.display = "none"; }
  function show(id) { qf(id).style.display = ""; }

  function effectiveRunning() { return runningServer || runningLocal; }
  function taskById(id) { for (var i = 0; i < tasks.length; i++) if (tasks[i].id === id) return tasks[i]; return null; }

  // ---- 引导 ----
  function boot() {
    var h = null;
    fetch(BASE + "/health", { headers: token ? { "X-Auth-Token": token } : {} })
      .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
      .then(function (json) {
        if (!json || json.ok !== true) { showError("服务返回异常"); return; }
        h = json;
        if (h.authEnabled && !token) { showToken(""); return; }
        if (h.authEnabled && token) {
          api("/status").then(function (r) {
            if (r.status === 401) { showToken("token 无效，请重新输入"); return; }
            if (r.ok) { startMain(); } else { showError("服务返回异常"); }
          }).catch(function () { showError("服务不可达"); });
        } else { startMain(); }
      })
      .catch(function (e) { showError("服务不可达（" + e.message + "）"); });
  }

  function showError(msg) { qf("errorMsg").textContent = msg; show("errorOverlay"); }
  function showToken(msg) { qf("tokenErr").textContent = msg || ""; qf("tokenInput").value = ""; show("tokenOverlay"); qf("tokenInput").focus(); }
  function forceToken(msg) {
    stopAllPoll();
    showToken(msg || "token 失效，请重新输入");
  }

  function persistToken() {
    if (!token) return;
    try { localStorage.setItem("mfa_token", token); } catch (e) {}
    try {
      var u = new URL(location.href);
      if (u.searchParams.get("token") !== token) {
        u.searchParams.set("token", token);
        history.replaceState(null, "", u.pathname + u.search);
      }
    } catch (e) {}
  }

  function startMain() {
    hide("tokenOverlay"); hide("errorOverlay");
    persistToken();
    loadStatus();
    statusPoll();
    probeAndStartVideo();
    openTab("ctrl");
    setBadge("连接中…", "");
  }

  // ---- 状态 ----
  function statusPoll() {
    clearInterval(poll.status);
    poll.status = setInterval(function () { loadStatus(); }, 2000);
  }
  function loadStatus() {
    api("/status").then(function (r) {
      if (r.status === 401) { forceToken("token 失效"); return null; }
      return r.ok ? r.json() : null;
    }).then(function (st) {
      if (!st) return;
      status = st;
      renderStatusBar(st);
      reconcileRunning(st);
      if (st.sessionActive && (videoState === "none" || videoState === "idle")) probeAndStartVideo();
      if (!st.sessionActive && videoState !== "none" && videoState !== "idle") setVideoNone();
    }).catch(function () {});
  }
  function renderStatusBar(st) {
    var dot = qf("dot");
    dot.className = "dot" + (st.sessionActive ? " on" : "");
    var parts = [];
    parts.push(st.sessionActive ? "会话●" : "会话○");
    parts.push("设备 " + (st.device || "?"));
    parts.push("RTC " + (st.webrtcPeers >= 0 ? st.webrtcPeers : "-"));
    parts.push("流 " + (st.streamClients || 0));
    if (st.accessibilityEnabled !== undefined) parts.push(st.accessibilityEnabled ? "无障碍开" : "无障碍关");
    if (st.authEnabled) parts.push("鉴权开"); else parts.push("鉴权关");
    if (effectiveRunning()) parts.push("执行中");
    qf("statusRight").textContent = parts.join(" · ");
  }
  function reconcileRunning(st) {
    var srv = st.runningTaskId || null;
    runningServer = srv;
    if (srv) {
      runningLocal = null;
      lastServerRunning = srv;
    } else {
      if (lastServerRunning) {
        runningLocal = null;
      } else if (runningLocal) {
        var t = taskById(runningLocal);
        if (t && t.lastStatus && t.lastStatus.length > 0) runningLocal = null;
      }
      lastServerRunning = null;
    }
    if (openDrawer === "auto") renderAuto();
  }

  // ---- 视频回显 ----
  function probeAndStartVideo() {
    setBadge("探测会话…", "");
    api("/frame").then(function (r) {
      if (r.status === 404 || !r.ok) { if (r.status === 401) { forceToken("token 失效"); return; } setVideoNone(); return; }
      hideHint();
      startRtc();
    }).catch(function () { setVideoNone(); });
  }
  function setVideoNone() {
    stopRtc();
    videoState = "none";
    qf("video").style.display = "none"; qf("mjpeg").style.display = "none";
    setBadge("暂无会话", "none");
    showHint("暂无会话：请先在设备上建立托管会话");
  }
  function showHint(t) { var h = qf("stageHint"); h.textContent = t; h.style.display = ""; }
  function hideHint() { qf("stageHint").style.display = "none"; }
  function setBadge(text, cls) { qf("modeBadge").textContent = text; qf("modeBadge").style.color = cls === "on" ? "#81c995" : (cls === "err" || cls === "none" ? "#f28b82" : "#fff"); }

  function startRtc() {
    if (videoState === "rtc" || videoState === "mjpeg") return;
    videoState = "connecting";
    var proto = location.protocol === "https:" ? "wss://" : "ws://";
    setupPeer();
    if (!pc) { fallbackMjpeg(); return; } // 无 WebRTC 能力（如非安全上下文/被禁）→ 立即降级，不开信令
    try { ws = new WebSocket(proto + location.host + "/webrtc/signaling?token=" + enc(token)); }
    catch (e) { ws = null; fallbackMjpeg(); return; }
    ws.onopen = function () { while (wsQueue.length) ws.send(wsQueue.shift()); };
    ws.onmessage = function (ev) { handleWs(ev.data); };
    ws.onerror = function () { fallbackMjpeg(); };
    ws.onclose = function () {
      if (videoState === "connecting" || videoState === "rtc") fallbackMjpeg();
    };
    rtcTimer = setTimeout(function () {
      if (videoState === "connecting") { stopRtc(); fallbackMjpeg(); }
    }, 6000);
  }
  function setupPeer() {
    try { pc = new RTCPeerConnection({ iceServers: [] }); }
    catch (e) { pc = null; fallbackMjpeg(); return; }
    pc.ondatachannel = function (e) { dc = e.channel; };
    pc.onicecandidate = function (e) {
      if (e.candidate) sendWs({ type: "ice", sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex, candidate: e.candidate.candidate });
    };
    pc.ontrack = function (e) {
      var v = qf("video");
      v.srcObject = (e.streams && e.streams[0]) ? e.streams[0] : new MediaStream([e.track]);
      v.style.display = ""; qf("mjpeg").style.display = "none";
      videoState = "rtc";
      clearTimeout(rtcTimer);
      setBadge("RTC 已连接", "on");
      hideHint();
    };
    pc.onconnectionstatechange = function () {
      var st = pc.connectionState;
      if (st === "failed" && videoState !== "mjpeg") fallbackMjpeg();
    };
  }
  function handleWs(data) {
    var d = null;
    try { d = JSON.parse(data); } catch (e) { return; }
    if (!d) return;
    if (!pc) { return; } // 降级后迟到的信令忽略
    if (d.type === "offer") {
      pc.setRemoteDescription({ type: "offer", sdp: d.sdp })
        .then(function () { return pc.createAnswer(); })
        .then(function (ans) { return pc.setLocalDescription(ans); })
        .then(function () { sendWs({ type: "answer", sdp: pc.localDescription.sdp }); })
        .catch(function () { fallbackMjpeg(); });
    } else if (d.type === "ice") {
      if (pc) pc.addIceCandidate({ sdpMid: d.sdpMid, sdpMLineIndex: d.sdpMLineIndex, candidate: d.candidate }).catch(function () {});
    } else if (d.type === "state") {
      if (videoState === "connecting" || videoState === "rtc") setBadge("RTC:" + d.state, d.state.indexOf("error") === 0 ? "err" : "");
    }
  }
  function sendWs(o) {
    var s = JSON.stringify(o);
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(s); else if (ws) wsQueue.push(s);
  }
  function stopRtc() {
    clearTimeout(rtcTimer);
    if (ws) { ws.onclose = null; try { ws.close(); } catch (e) {} ws = null; }
    if (pc) { try { pc.close(); } catch (e) {} pc = null; }
    dc = null;
    wsQueue = [];
  }
  function fallbackMjpeg() {
    if (videoState === "mjpeg") return;
    stopRtc();
    videoState = "mjpeg";
    var img = qf("mjpeg");
    img.style.display = ""; qf("video").style.display = "none";
    var u = urlWithToken("/stream");
    if (img.getAttribute("src") !== u) img.src = u;
    setBadge("MJPEG 降级", "warn");
    hideHint();
  }

  // ---- 输入注入（RTC: DataChannel；MJPEG: REST） ----
  var down = null;
  function stageN(e) {
    var isImg = videoState === "mjpeg";
    var el = isImg ? qf("mjpeg") : qf("video");
    var r = el.getBoundingClientRect();
    var nw = isImg ? el.naturalWidth : el.videoWidth;
    var nh = isImg ? el.naturalHeight : el.videoHeight;
    if (!nw || !nh || !r.width || !r.height) return null;
    var scale = Math.min(r.width / nw, r.height / nh);
    var dw = nw * scale, dh = nh * scale;
    var ox = (r.width - dw) / 2, oy = (r.height - dh) / 2;
    var x = e.clientX - r.left, y = e.clientY - r.top;
    var nx = (x - ox) / dw, ny = (y - oy) / dh;
    if (nx < 0 || ny < 0 || nx > 1 || ny > 1) return null;
    return { nx: nx, ny: ny };
  }
  function px(v, max) { return Math.round(Math.max(0, Math.min(1, v)) * max); }
  function mediaDims() {
    var img = qf("mjpeg");
    return { w: img.naturalWidth || 0, h: img.naturalHeight || 0 };
  }
  function doTap(nx, ny) {
    if (videoState === "rtc" && dc && dc.readyState === "open") {
      dc.send(JSON.stringify({ t: "tap", nx: nx, ny: ny }));
    } else if (videoState === "mjpeg") {
      var d = mediaDims(); if (!d.w) return;
      api("/action/tap", { method: "POST", body: JSON.stringify({ x: px(nx, d.w - 1), y: px(ny, d.h - 1) }) }).catch(function () {});
    }
  }
  function doSwipe(x1, y1, x2, y2, dur) {
    if (videoState === "rtc" && dc && dc.readyState === "open") {
      dc.send(JSON.stringify({ t: "swipe", nx1: x1, ny1: y1, nx2: x2, ny2: y2, durMs: dur }));
    } else if (videoState === "mjpeg") {
      var d = mediaDims(); if (!d.w) return;
      api("/action/swipe", { method: "POST", body: JSON.stringify({ x1: px(x1, d.w - 1), y1: px(y1, d.h - 1), x2: px(x2, d.w - 1), y2: px(y2, d.h - 1), durMs: dur }) }).catch(function () {});
    }
  }
  function doKey(code) {
    if (videoState === "rtc" && dc && dc.readyState === "open") {
      dc.send(JSON.stringify({ t: "key", code: code }));
    } else if (videoState === "mjpeg") {
      api("/action/key", { method: "POST", body: JSON.stringify({ code: code }) }).catch(function () {});
    }
  }

  // ---- 菜单 / 抽屉 ----
  function openTab(tab) {
    activeTab = tab;
    var tabs = document.querySelectorAll(".tab");
    for (var i = 0; i < tabs.length; i++) tabs[i].className = "tab" + (tabs[i].getAttribute("data-tab") === tab ? " active" : "");
    if (tab === "ctrl") { closeAllDrawers(); return; }
    openDrawerOf(tab);
  }
  function openDrawerOf(name) {
    closeAllDrawers(true);
    openDrawer = name;
    qf("backdrop").style.display = "";
    qf("drawer-" + name).classList.add("open");
    if (name === "apps") renderApps();
    else if (name === "auto") { refreshAuto(); autoPoll(); }
    else if (name === "logs") { refreshLogs(); logPoll(); }
  }
  function closeAllDrawers(keepBackdrop) {
    ["apps", "auto", "logs"].forEach(function (n) { qf("drawer-" + n).classList.remove("open"); });
    stopDrawerPoll();
    if (!keepBackdrop) { openDrawer = null; }
    qf("backdrop").style.display = "none";
  }
  function stopDrawerPoll() {
    clearInterval(poll.auto); clearInterval(poll.logs);
  }
  function stopAllPoll() {
    clearInterval(poll.status); clearInterval(poll.auto); clearInterval(poll.logs);
  }

  // ---- 应用抽屉 ----
  function renderApps() {
    var box = qf("appsList"); box.innerHTML = "";
    api("/apps").then(function (r) {
      if (r.status === 401) { forceToken("token 失效"); return null; }
      return r.ok ? r.json() : null;
    }).then(function (list) {
      if (!list) return;
      list.forEach(function (a) {
        appsMap[a.packageName] = a.label;
        box.appendChild(appTile(a));
      });
    }).catch(function () {});
  }
  function appTile(a) {
    var d = el("div", "tile");
    var img = document.createElement("img");
    img.className = "tile-ico"; img.alt = "";
    img.src = urlWithToken("/apps/" + enc(a.packageName) + "/icon");
    var name = el("span", "tile-name", a.label);
    d.appendChild(img); d.appendChild(name);
    d.addEventListener("click", function () { launchApp(a.packageName); });
    return d;
  }
  function launchApp(pkg) {
    api("/session/launch", { method: "POST", body: JSON.stringify({ pkg: pkg }) })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.ok) { showToast("已切换：" + (appsMap[pkg] || pkg)); closeAllDrawers(); }
        else showToast("启动失败：" + ((res && res.message) || "未知错误"));
      }).catch(function (e) { showToast("启动失败：" + e.message); });
  }

  // ---- 自动化抽屉 ----
  function autoPoll() {
    clearInterval(poll.auto);
    poll.auto = setInterval(function () { refreshAuto(); }, 2000);
  }
  function refreshAuto() {
    api("/tasks").then(function (r) {
      if (r.status === 401) { forceToken("token 失效"); return null; }
      return r.ok ? r.json() : null;
    }).then(function (list) {
      if (!list) return;
      tasks = list;
      if (openDrawer === "auto") renderAuto();
    }).catch(function () {});
  }
  function renderAuto() {
    var box = qf("autoList"); box.innerHTML = "";
    if (!tasks || tasks.length === 0) { box.appendChild(el("div", "empty", "暂无自动化任务，点「＋ 新建」创建")); return; }
    tasks.forEach(function (t) { box.appendChild(autoRow(t)); });
  }
  function scheduleLabel(t) {
    if (t.kind === "INTERVAL") return "间隔 " + (t.intervalMs >= 1000 ? Math.round(t.intervalMs / 100) / 10 + " 秒" : t.intervalMs + " ms");
    if (t.kind === "CRON") return "cron: " + t.cron;
    return "立即执行";
  }
  function autoRow(t) {
    var d = el("div", "arow");
    var img = document.createElement("img");
    img.className = "tile-ico"; img.alt = "";
    if (t.packageName) img.src = urlWithToken("/apps/" + enc(t.packageName) + "/icon");
    var acol = el("div", "acol");
    acol.appendChild(el("div", "aname", t.name || t.id));
    var label = t.packageName ? (appsMap[t.packageName] || t.packageName) : "未指定 App";
    acol.appendChild(el("div", "asub", label + " · " + scheduleLabel(t)));
    if (t.lastStatus && t.lastStatus.length > 0) {
      var last = el("div", "alast" + (t.lastStatus.indexOf("error") === 0 ? " err" : ""), "上次：" + t.lastStatus);
      acol.appendChild(last);
    }
    var actr = el("div", "actr");
    var sw = document.createElement("label");
    sw.className = "switch";
    var cb = document.createElement("input"); cb.type = "checkbox"; cb.checked = !!t.enabled;
    sw.appendChild(cb); sw.appendChild(document.createElement("span"));
    cb.addEventListener("change", function () { toggleTask(t, cb.checked); });
    actr.appendChild(sw);
    if (t.enabled) {
      var running = effectiveRunning();
      if (running === t.id) { actr.appendChild(mkBtn("⏹ 停止", function () { stopTask(t); })); }
      else if (!running) { actr.appendChild(mkBtn("▶ 执行", function () { runTask(t); })); }
    }
    actr.appendChild(mkBtn("编辑", function () { openEdit(t); }));
    d.appendChild(img); d.appendChild(acol); d.appendChild(actr);
    return d;
  }
  function mkBtn(label, fn) { var b = el("button", "btn sm", label); b.addEventListener("click", fn); return b; }
  function runTask(t) {
    api("/tasks/" + enc(t.id) + "/run", { method: "POST" })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.ok) {
          runningLocal = t.id; lastServerRunning = null;
          showToast("已调度执行：" + (t.name || t.id));
          renderAuto();
        } else showToast("无法执行：已有自动化在执行");
      }).catch(function (e) { showToast("执行失败：" + e.message); });
  }
  function stopTask(t) {
    api("/tasks/" + enc(t.id) + "/stop", { method: "POST" })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.ok) {
          runningLocal = null; runningServer = null; lastServerRunning = null;
          showToast("已停止：" + (t.name || t.id));
          renderAuto();
        } else showToast("停止失败");
      }).catch(function (e) { showToast("停止失败：" + e.message); });
  }
  function toggleTask(t, enabled) {
    var body = { id: t.id, name: t.name, packageName: t.packageName || null, script: t.script, enabled: enabled, kind: t.kind, intervalMs: t.intervalMs, cron: t.cron };
    api("/tasks", { method: "POST", body: JSON.stringify(body) })
      .then(function (r) { return r.json(); })
      .then(function (res) { if (res && res.ok) { t.enabled = enabled; showToast(enabled ? "已启用" : "已停用"); renderAuto(); } else showToast("保存失败"); })
      .catch(function () { showToast("保存失败"); });
  }
  function openEdit(t) {
    editing = t || null;
    qf("editTitle").textContent = t ? "编辑自动化" : "新建自动化";
    qf("f_name").value = t ? t.name : "";
    qf("f_pkg").value = t ? (t.packageName || "") : "";
    qf("f_enabled").checked = t ? !!t.enabled : true;
    qf("f_kind").value = t ? t.kind : "IMMEDIATE";
    qf("f_interval").value = t ? t.intervalMs : "";
    qf("f_cron").value = t ? t.cron : "";
    qf("f_script").value = t ? t.script : "// await host.invoke('screen.tap', { x: 360, y: 500 })";
    qf("editErr").textContent = "";
    show("editModal");
  }
  function showEditErr(m) { qf("editErr").textContent = m; }
  function saveEdit() {
    var name = qf("f_name").value.trim();
    var kind = qf("f_kind").value;
    var intervalMs = parseInt(qf("f_interval").value, 10) || 0;
    var cron = qf("f_cron").value.trim();
    if (!name) { showEditErr("请填写任务名称"); return; }
    if (kind === "INTERVAL" && intervalMs <= 0) { showEditErr("间隔执行需填写大于 0 的毫秒数"); return; }
    var body = {
      name: name,
      packageName: qf("f_pkg").value.trim() || null,
      script: qf("f_script").value,
      enabled: qf("f_enabled").checked,
      kind: kind,
      intervalMs: kind === "INTERVAL" ? intervalMs : 0,
      cron: kind === "CRON" ? cron : ""
    };
    if (editing) body.id = editing.id;
    api("/tasks", { method: "POST", body: JSON.stringify(body) })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.ok) { hide("editModal"); editing = null; refreshAuto(); showToast("已保存"); }
        else showEditErr("保存失败");
      }).catch(function (e) { showEditErr("保存失败：" + e.message); });
  }

  // ---- 日志抽屉 ----
  function logPoll() {
    clearInterval(poll.logs);
    poll.logs = setInterval(function () { refreshLogs(); }, 3000);
  }
  function refreshLogs() {
    var lv = qf("logLevel").value;
    api("/logs" + (lv ? "?level=" + lv : "")).then(function (r) {
      if (r.status === 401) { forceToken("token 失效"); return null; }
      return r.ok ? r.json() : null;
    }).then(function (list) {
      if (!list) return;
      var box = qf("logList"); box.innerHTML = "";
      list.forEach(function (e) {
        var d = el("div", "lrow");
        d.appendChild(el("span", "lt", e.time));
        d.appendChild(el("span", "ll " + e.level.toLowerCase(), e.level));
        d.appendChild(el("span", "ltag", e.tag));
        d.appendChild(el("span", "lmsg", e.msg));
        box.appendChild(d);
      });
      box.scrollTop = box.scrollHeight;
    }).catch(function () {});
  }

  // ---- 事件绑定 ----
  function bindEvents() {
    document.getElementById("menu").addEventListener("click", function (e) {
      var b = e.target.closest(".tab"); if (!b) return;
      openTab(b.getAttribute("data-tab"));
    });
    qf("backdrop").addEventListener("click", function () { closeAllDrawers(); });
    qf("tokenOk").addEventListener("click", submitToken);
    qf("tokenInput").addEventListener("keydown", function (e) { if (e.key === "Enter") submitToken(); });
    qf("tokenRefresh").addEventListener("click", function () { location.reload(); });
    qf("errorRefresh").addEventListener("click", function () { hide("errorOverlay"); location.reload(); });
    qf("autoNew").addEventListener("click", function () { openEdit(null); });
    qf("autoRefresh").addEventListener("click", refreshAuto);
    qf("editSave").addEventListener("click", saveEdit);
    qf("editCancel").addEventListener("click", function () { hide("editModal"); editing = null; });
    qf("logClear").addEventListener("click", function () {
      api("/logs/clear", { method: "POST" }).then(function () { refreshLogs(); }).catch(function () {});
    });
    qf("logLevel").addEventListener("change", refreshLogs);

    var stage = qf("stage");
    stage.addEventListener("pointerdown", function (e) {
      e.preventDefault();
      var p = stageN(e); if (!p) return;
      down = p;
    });
    stage.addEventListener("pointermove", function (e) {
      if (!down) return;
      var p = stageN(e); if (!p) return;
      var dx = p.nx - down.nx, dy = p.ny - down.ny;
      if (dx * dx + dy * dy > 0.003) { doSwipe(down.nx, down.ny, p.nx, p.ny, 120); down = null; }
    });
    stage.addEventListener("pointerup", function (e) {
      var p = stageN(e);
      if (down && p) doTap(p.nx, p.ny);
      down = null;
    });
    stage.addEventListener("pointercancel", function () { down = null; });
    window.addEventListener("blur", function () { down = null; });
    document.addEventListener("keydown", function (e) {
      if (e.code === "Backspace") { e.preventDefault(); doKey(4); }
      else if (e.code === "Enter") { if (e.target && e.target.tagName && e.target.tagName === "INPUT") return; e.preventDefault(); doKey(66); }
      else if (e.code === "Space") { e.preventDefault(); doKey(62); }
      else if (e.code === "Home") { e.preventDefault(); doKey(3); }
    });
  }

  function submitToken() {
    var v = qf("tokenInput").value.trim();
    if (!v) { qf("tokenErr").textContent = "请输入 token"; return; }
    token = v;
    persistToken();
    api("/status").then(function (r) {
      if (r.status === 401) { qf("tokenErr").textContent = "token 无效，请重新输入"; return; }
      if (r.ok) { hide("tokenOverlay"); startMain(); }
      else qf("tokenErr").textContent = "服务返回异常";
    }).catch(function () { qf("tokenErr").textContent = "服务不可达"; });
  }

  // 先绑定静态事件（含 token 浮窗按钮），再启动引导；token 浮窗出现时也必须可交互。
  bindEvents();
  boot();
})();
</script>
</body>
</html>
""".trimIndent()
}
