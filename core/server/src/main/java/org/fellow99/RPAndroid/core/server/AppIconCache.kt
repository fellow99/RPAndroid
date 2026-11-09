package org.fellow99.RPAndroid.core.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.io.ByteArrayOutputStream

/**
 * [AppIconCache] —— 应用图标 PNG 内存缓存（008-client `/apps/{pkg}/icon`）。
 *
 * 经 server 注入的 appContext.packageManager 取图标 → Bitmap → PNG；按 pkg 缓存，
 * 避免列表/抽屉反复加载时重复编码。超大（>1MB）不缓存。
 */
object AppIconCache {

    /** 缓存总字节上限（移动设备内存保护）。 */
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val cache = object : LruCache<String, ByteArray>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** 返回 pkg 的图标 PNG 字节；未知包/不可用时返回 null。 */
    fun icon(context: Context, pkg: String): ByteArray? {
        cache.get(pkg)?.let { return it }
        val bytes = runCatching {
            val pm = context.packageManager
            val d: Drawable = pm.getApplicationIcon(pkg)
            val bmp = d.toBitmapSafely()
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }.getOrNull() ?: return null
        // 单图标超大（>MAX_BYTES）不会被缓存（LruCache 立即淘汰），直接返回但不缓存。
        cache.put(pkg, bytes)
        return bytes
    }

    private fun Drawable.toBitmapSafely(): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap
        val w = intrinsicWidth.takeIf { it > 0 } ?: 48
        val h = intrinsicHeight.takeIf { it > 0 } ?: 48
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        setBounds(0, 0, c.width, c.height)
        draw(c)
        setBounds(0, 0, 0, 0) // 还原 bounds，避免影响其它使用
        return b
    }
}
