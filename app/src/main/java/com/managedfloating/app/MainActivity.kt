package com.managedfloating.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.managedfloating.app.ui.AppShell
import com.managedfloating.app.ui.theme.ManagedTheme

/**
 * 主界面入口：Compose 壳 + 底部三栏目导航（应用 / 自动化 / 设置）。
 * 服务器与会话由 [ManagedRuntime] 幂等承载；本 Activity 只负责 UI。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ManagedRuntime.start(applicationContext)
        setContent {
            ManagedTheme {
                AppShell(onFinish = { finish() })
            }
        }
    }
}
