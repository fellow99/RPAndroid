package top.fellow99.rpandroid.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.Text
import top.fellow99.rpandroid.present.FramePoller
import top.fellow99.rpandroid.present.TouchMapper
import top.fellow99.rpandroid.core.controller.ScreenController

/**
 * 嵌入呈现：在给定区域渲染被托管虚拟屏画帧（letterbox 等比缩放），触摸经 [TouchMapper]
 * 换算回写注入到虚拟屏。会话未就绪时控制器返回 null 帧，界面显示"等待画面"。
 */
@Composable
fun EmbeddedScreen(controller: ScreenController, modifier: Modifier = Modifier) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var poller by remember { mutableStateOf<FramePoller?>(null) }

    DisposableEffect(controller) {
        val p = FramePoller(controller)
        p.start { bmp -> bitmap = bmp }
        poller = p
        onDispose {
            p.stop()
            bitmap?.recycle()
            bitmap = null
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { viewSize = it }
            .pointerInput(controller) {
                detectTapGestures { offset ->
                    val p = poller ?: return@detectTapGestures
                    val mapped = TouchMapper.map(
                        touchX = offset.x,
                        touchY = offset.y,
                        viewW = viewSize.width,
                        viewH = viewSize.height,
                        virtualWidth = p.virtualWidth,
                        virtualHeight = p.virtualHeight,
                    )
                    if (mapped != null) {
                        Thread { runCatching { controller.tap(mapped.first, mapped.second) } }.start()
                    }
                }
            },
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                "等待画面…",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
