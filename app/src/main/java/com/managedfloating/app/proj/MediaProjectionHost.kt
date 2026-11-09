package com.managedfloating.app.proj

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import com.managedfloating.core.controller.AppScreenController
import com.managedfloating.core.controller.FrameSource
import com.managedfloating.core.server.MfaLog

/**
 * [MediaProjectionHost] —— Mode B（主屏承载）授权与投影宿主（010-display-mode）。
 *
 * 职责：
 * - [authorizationIntent]：返回系统「开始录制/投屏」授权 Intent（Activity Result 起）。
 * - [onGranted]：授权成功回调，启动 [ProjectionService]，并把其投影帧源接入
 *   [AppScreenController].projectionFrameSource（Mode B 帧源），随后切 mode。
 * - [onDenied] / [revert]：授权被拒 / 需要回 A 时，停止投影服务并清空帧源。
 *
 * 授权必须先经系统弹窗（无法静默）。Android 14+ 在 [ProjectionService]（mediaProjection 前台服务）内
 * 创建投影 VirtualDisplay。
 */
object MediaProjectionHost {

    /** 生成 MediaProjection 系统授权 Intent（Activity Result Launcher 使用）。 */
    fun authorizationIntent(context: Context): Intent? {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        return mpm?.createScreenCaptureIntent()
    }

    /**
     * 授权成功：启动 [ProjectionService] 建投影，并把帧源接入 controller，然后更新 mode 到 MAIN。
     * @return true 表示已启动投影流程。
     */
    fun onGranted(context: Context, controller: AppScreenController, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            MfaLog.w("Projection", "authorization not OK (code=$resultCode); keep Mode A")
            return false
        }
        ProjectionService.onReady = { source: FrameSource ->
            controller.projectionFrameSource = source
            controller.switchDisplayMode(com.managedfloating.core.controller.DisplayMode.MAIN)
            MfaLog.i("Projection", "Mode B ready; projection frame source attached")
            ProjectionService.onReady = null
        }
        val intent = ProjectionService.intent(context, resultCode, data)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
            return true
        } catch (e: Exception) {
            MfaLog.w("Projection", "start ProjectionService failed: ${e.message}")
            ProjectionService.onReady = null
            return false
        }
    }

    /** 授权被拒 / 失败 / 需要回 A：停止投影服务并清空 controller 的投影帧源与 Mode B 状态。 */
    fun onDenied(context: Context, controller: AppScreenController) {
        controller.projectionFrameSource = null
        context.stopService(Intent(context, ProjectionService::class.java))
        // 若仍在 MAIN，则退回虚拟屏承载（回滚，FR-010-004）
        if (controller.displayMode == com.managedfloating.core.controller.DisplayMode.MAIN) {
            controller.switchDisplayMode(com.managedfloating.core.controller.DisplayMode.VIRTUAL)
        }
    }
}
