package top.fellow99.rpauto.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 把 Drawable 转成 Bitmap（应用图标）。 */
fun Drawable.toAppBitmap(): Bitmap? {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val w = intrinsicWidth.takeIf { it > 0 } ?: 48
    val h = intrinsicHeight.takeIf { it > 0 } ?: 48
    return runCatching {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            setBounds(0, 0, c.width, c.height)
            draw(c)
            setBounds(0, 0, 0, 0) // 还原，避免影响其它使用
        }
    }.getOrNull()
}

@Composable
fun rememberAppIcon(pkg: String?): Bitmap? {
    if (pkg.isNullOrEmpty()) return null
    val context = LocalContext.current.applicationContext
    // produceState：仅在 pkg 变化时启动一次加载，pkg 不变不会重复触发
    return produceState<Bitmap?>(initialValue = null, key1 = pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(pkg).toAppBitmap() }.getOrNull()
        }
    }.value
}

/** 图标 + 可选名称。 */
@Composable
fun AppIconAndLabel(
    pkg: String?,
    label: String?,
    iconSize: Dp = 36.dp,
) {
    val icon = rememberAppIcon(pkg)
    if (icon != null) {
        Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = label,
            modifier = Modifier.size(iconSize),
            contentScale = ContentScale.Fit,
        )
    } else if (label != null) {
        Text(
            label.take(1),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(iconSize),
        )
    }
}
