package com.managedfloating.core.controller

/**
 * [ScreenController] —— 统一操作入口（架构 B）占位。
 *
 * 被 REST / MCP / 脚本 / 用户触摸共用的唯一控制器。
 * 后续里程碑实现：
 *  - 读：单帧截图（PNG/JPEG）、MJPEG 帧源、无障碍控件树 → 元素列表
 *  - 写：点击 / 滑动 / 长按 / 输入 / 按键 / 返回 / 启动 / 切换
 *  - 把帧交给呈现模式与视频流；把触摸坐标回写虚拟屏
 */
interface ScreenController {
    fun isActive(): Boolean
}
