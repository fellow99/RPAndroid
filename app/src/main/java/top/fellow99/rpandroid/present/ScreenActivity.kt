package top.fellow99.rpandroid.present

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.fellow99.rpandroid.ui.EmbeddedScreen
import top.fellow99.rpandroid.ui.PresentationHub
import top.fellow99.rpandroid.ui.PresentationMode
import top.fellow99.rpandroid.ui.theme.ManagedTheme
import top.fellow99.rpandroid.core.controller.ScreenController

/**
 * [ScreenActivity] —— **全屏模式**：全屏承载被托管 App 画面，触摸回写。
 *
 * 系统返回键转发给被托管 App（FR-101-9）；右上角提供 嵌入 / 浮窗 / 关闭 悬浮控制。
 * 退出时若仍是全屏模式则回落为嵌入（保留 hostedApp）。
 */
class ScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = ManagedBridge.controller()
        if (controller == null) {
            setContentView(TextView(this).apply { text = "会话未就绪：请先在主界面启动会话" })
            return
        }
        PresentationHub.setMode(PresentationMode.FULLSCREEN)

        val onAction: (String) -> Unit = { action ->
            when (action) {
                "embed" -> {
                    PresentationHub.setMode(PresentationMode.EMBEDDED)
                    finish()
                }
                "floating" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                        Toast.makeText(this, "请授予“显示在其他应用上层”，再点浮窗模式", Toast.LENGTH_LONG).show()
                    } else {
                        FloatingService.start(this)
                        PresentationHub.setMode(PresentationMode.FLOATING)
                        finish()
                    }
                }
                "close" -> {
                    FloatingService.stop(this)
                    PresentationHub.clearHosted()
                    finish()
                }
            }
        }

        setContent {
            ManagedTheme {
                FullscreenPresentation(controller = controller, onAction = onAction)
            }
        }
    }

    override fun onDestroy() {
        if (PresentationHub.presentationMode == PresentationMode.FULLSCREEN) {
            PresentationHub.setMode(PresentationMode.EMBEDDED)
        }
        super.onDestroy()
    }
}

/** 全屏呈现：画帧 + 右上角圆形小图标控制（嵌入/浮窗/关闭）+ 返回键转发。 */
@Composable
private fun FullscreenPresentation(controller: ScreenController, onAction: (String) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        EmbeddedScreen(controller, Modifier.fillMaxSize())
        Row(
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircleIconButton(icon = "⊞", onClick = { onAction("embed") })     // 嵌入
            CircleIconButton(icon = "▣", onClick = { onAction("floating") })  // 浮窗
            CircleIconButton(icon = "✕", onClick = { onAction("close") })     // 关闭
        }
        // 返回键转发给被托管 App（不退出全屏）
        BackHandler { Thread { runCatching { controller.back() } }.start() }
    }
}

/** 圆形小图标按钮：直径约 1.5em，半透明深色底 + 白色图标。 */
@Composable
private fun CircleIconButton(icon: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color(0x99000000),
        modifier = Modifier.size(24.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(icon, color = Color.White, fontSize = 15.sp)
        }
    }
}
