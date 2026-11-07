package com.managedfloating.core.server

/**
 * [ManagedServer] —— 对外接口层（架构 D）占位。
 *
 * 后续里程碑实现（均复用 [com.managedfloating.core.controller.ScreenController]）：
 *  - REST API（Ktor）：列 App / 启动 / 点击 / 滑动 / 截图 / 读 UI / 任务调度
 *  - MCP server（SSE / streamable HTTP）：把操作与调度封装成 agent 工具
 *  - 视频流：一期 MJPEG-over-HTTP，二期 WebRTC
 *  - 绑定 localhost 端口；USB 用 adb reverse 映射；局域网直连
 */
interface ManagedServer {
    fun start(): Boolean
    fun stop()
}
