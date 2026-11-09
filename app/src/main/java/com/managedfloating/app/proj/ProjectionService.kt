package com.managedfloating.app.proj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.managedfloating.core.controller.FrameHub
import com.managedfloating.core.controller.FrameSource
import com.managedfloating.core.server.MfaLog

/**
 * [ProjectionService] —— 承载 MediaProjection 的**前台服务**（010-display-mode Mode B）。
 *
 * - Android 14+（API 34+）：`createVirtualDisplay` 必须在 `foregroundServiceType="mediaProjection"`
 *   的前台服务内调用，故投影生命周期放本服务。
 * - 持有授权 token（MediaProjection 实例），在其上创建投影 VirtualDisplay，把主屏画面镜像到
 *   [frameHub]（复用 [FrameHub] 的 Surface+ImageReader 收帧逻辑）作为 [FrameSource] 抛出，
 *   供 [com.managedfloating.core.controller.AppScreenController].projectionFrameSource 使用。
 * - [stop] 时调 `projection.stop()` 并释放 [FrameHub]，避免 token/线程泄漏。
 */
class ProjectionService : Service() {

    private var mpm: MediaProjectionManager? = null
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var frameHub: FrameHub? = null

    override fun onCreate() {
        super.onCreate()
        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (code != 0 && data != null) {
            startProjection(code, data)
        } else {
            MfaLog.w("Projection", "no mediaProjection token (code=$code data=$data) -> stop")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val manager = mpm ?: return stopSelfQuietly()
        stopProjection()
        try {
            val mp = manager.getMediaProjection(resultCode, data) ?: return stopSelfQuietly()
            projection = mp
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val pt = android.graphics.Point()
            wm.defaultDisplay?.getRealSize(pt)
            val w = if (pt.x > 0) pt.x else resources.displayMetrics.widthPixels
            val h = if (pt.y > 0) pt.y else resources.displayMetrics.heightPixels
            val dpi = resources.displayMetrics.densityDpi
            val hub = FrameHub(w, h)
            frameHub = hub
            // API 36 重载为 (name,w,h,dpi,int flags,Surface,Callback,Handler)；flags=0 表示镜像默认主屏。
            vd = mp.createVirtualDisplay("mfa-projection", w, h, dpi, 0, hub.surface, null, null)
            MfaLog.i("Projection", "projection ready ${w}x${h} dpi=$dpi")
            onReady?.invoke(hub)
        } catch (t: Throwable) {
            MfaLog.e("Projection", "projection start failed", t)
            stopProjection()
            stopSelfQuietly()
        }
    }

    private fun stopProjection() {
        runCatching { vd?.release() }.onFailure { }
        vd = null
        runCatching { projection?.stop() }.onFailure { }
        projection = null
        frameHub?.release()
        frameHub = null
    }

    override fun onDestroy() {
        stopProjection()
        onStopped?.invoke()
        onStopped = null
        onReady = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopSelfQuietly() {
        // 不在此 startForeground(0,...)：id=0 非法且前台类型不匹配会在 API 34+ 崩溃（评审 I-2）。
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "主屏承载", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("主屏承载模式")
            .setContentText("正在捕获主屏画面，仅限局域网/USB")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            runCatching { startForeground(2, n) }
        }
    }

    companion object {
        private const val CHANNEL = "mfa_projection"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"

        /** 投影帧源就绪回调（app 层注入，把 hub 接给 AppScreenController.projectionFrameSource）。 */
        @Volatile
        var onReady: ((FrameSource) -> Unit)? = null

        /**
         * 投影意外停止/被回收回调（app 层注入，用于 token 失效时回滚 Mode A，FR-010-004）。
         * 在 [onDestroy] 中触发；主动释放（[MediaProjectionHost.releaseProjection]）前会先置 null 避免递归。
         */
        @Volatile
        var onStopped: (() -> Unit)? = null

        fun intent(context: Context, code: Int, data: Intent): Intent =
            Intent(context, ProjectionService::class.java)
                .putExtra(EXTRA_RESULT_CODE, code)
                .putExtra(EXTRA_DATA, data)
    }
}
