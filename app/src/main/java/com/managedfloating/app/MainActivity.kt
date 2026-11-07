package com.managedfloating.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * 应用入口（000-scaffold 占位，纯 View 形态以降低脚手架构建风险）。
 *
 * 后续里程碑将替换为 Compose UI，并在 Scaffold 内接入：
 *  - 被托管 App 选择与配置（F）
 *  - 三种呈现模式（浮窗 / 常规 / headless）（C）
 *  - 任务 / 脚本管理（E）
 *  - 状态 / 日志视图
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "ManagedFloatingApp\n(scaffold placeholder)"
                textSize = 20f
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            },
        )
    }
}
