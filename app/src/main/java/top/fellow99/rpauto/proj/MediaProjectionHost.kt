package top.fellow99.rpauto.proj

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import top.fellow99.rpauto.core.controller.AppScreenController
import top.fellow99.rpauto.core.controller.FrameSource
import top.fellow99.rpauto.core.server.MfaLog

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
        // 投影意外停止/被系统回收（token 失效）时回滚 Mode A（FR-010-004）。
        ProjectionService.onStopped = {
            if (controller.displayMode == top.fellow99.rpauto.core.controller.DisplayMode.MAIN) {
                MfaLog.w("Projection", "projection stopped unexpectedly; reverting to Mode A")
                controller.switchDisplayMode(top.fellow99.rpauto.core.controller.DisplayMode.VIRTUAL)
            }
        }
        ProjectionService.onReady = { source: FrameSource ->
            // 先挂帧源，再切模式：switchDisplayMode(MAIN) 会触发跨屏迁移（本应用→虚拟屏、承载→主屏）。
            controller.projectionFrameSource = source
            // 守卫（FR-010-005）：若被拒（运行任务中），释放投影回滚，不挂模式。
            val denied = controller.switchDisplayMode(top.fellow99.rpauto.core.controller.DisplayMode.MAIN)
            if (denied != null) {
                MfaLog.w("Projection", "switch to MAIN denied: $denied; reverting projection")
                releaseProjection(context, controller)
            } else {
                MfaLog.i("Projection", "Mode B ready; projection frame source attached")
            }
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

    /** 授权被拒 / token 失效 / 切换回虚拟屏：停止投影服务并清空投影帧源（不切换模式本身）。 */
    fun releaseProjection(context: Context, controller: AppScreenController) {
        // 先撤销失效回调，避免 stopService → onDestroy → onStopped 与外部 mode 切换形成递归。
        ProjectionService.onStopped = null
        controller.projectionFrameSource = null
        context.stopService(Intent(context, ProjectionService::class.java))
    }

    /** 保留别名：释放投影（语义与 [releaseProjection] 一致）。 */
    fun onDenied(context: Context, controller: AppScreenController) = releaseProjection(context, controller)
}
