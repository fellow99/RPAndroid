package top.fellow99.rpandroid

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import top.fellow99.rpandroid.ui.AppShell
import top.fellow99.rpandroid.ui.theme.ManagedTheme

/**
 * 主界面入口：Compose 壳 + 底部三栏目导航（应用 / 自动化 / 设置）。
 * 服务器与会话由 [ManagedRuntime] 幂等承载；本 Activity 只负责 UI。
 *
 * 010-display-mode：Mode B（主屏承载）下对本应用窗口加 [WindowManager.LayoutParams.FLAG_SECURE]，
 * 使本应用 UI 在 MediaProjection 录制/镜像里显示黑块，不泄漏进投录流（首版不迁移本应用窗口）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ManagedRuntime.start(applicationContext)
        setContent {
            ManagedTheme {
                // Mode B 时开启 FLAG_SECURE；切回 Mode A 时移除。
                LaunchedEffect(top.fellow99.rpandroid.ui.PresentationHub.displayMode) {
                    val secure = top.fellow99.rpandroid.ui.PresentationHub.displayMode ==
                        top.fellow99.rpandroid.core.controller.DisplayMode.MAIN
                    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                AppShell(onFinish = { finish() })
            }
        }
    }
}
