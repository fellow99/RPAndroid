package com.managedfloating.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.managedfloating.core.controller.AppInfo

/** 呈现方式：嵌入（应用栏目主区域）/ 全屏（独立 Activity）/ 浮窗（悬浮窗服务）。 */
enum class PresentationMode { EMBEDDED, FULLSCREEN, FLOATING }

/**
 * 应用级跨 Activity 呈现状态（单例，Compose 可观察）。
 *
 * 由 MainActivity / ScreenActivity / FloatingService 同步，保证在三种呈现之间切换时不丢失
 * "当前承载应用"；同时承载全局唯一"正在执行的自动化任务 id"（互斥语义）。
 */
object PresentationHub {

    /** 当前承载的应用；null 表示未承载（应用栏目显示图标网格）。 */
    var hostedApp by mutableStateOf<AppInfo?>(null)
        private set

    /** 当前呈现方式。 */
    var presentationMode by mutableStateOf(PresentationMode.EMBEDDED)
        private set

    /** 正在执行的自动化任务 id（全局唯一；null 表示空闲）。 */
    var runningTaskId by mutableStateOf<String?>(null)
        private set

    /** 010-display-mode：当前承载模式（A 虚拟屏承载 / B 主屏承载），供 UI 展示「主屏」状态。 */
    var displayMode by mutableStateOf<com.managedfloating.core.controller.DisplayMode>(
        com.managedfloating.core.controller.DisplayMode.VIRTUAL)
        private set

    fun updateDisplayMode(mode: com.managedfloating.core.controller.DisplayMode) {
        displayMode = mode
    }

    fun hostApp(app: AppInfo) {
        hostedApp = app
        presentationMode = PresentationMode.EMBEDDED
    }

    /** 远程/服务端来源的承载状态同步：只更新承载 App，不改出示呈现方式（不打断全屏/浮窗）。 */
    fun setHosted(app: AppInfo?) {
        hostedApp = app
    }

    fun setMode(mode: PresentationMode) {
        presentationMode = mode
    }

    /** 关闭呈现：回到"未承载应用"状态（应用栏目显示图标网格）。 */
    fun clearHosted() {
        hostedApp = null
        presentationMode = PresentationMode.EMBEDDED
    }

    fun setRunning(taskId: String?) {
        runningTaskId = taskId
    }
}
