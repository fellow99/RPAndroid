package top.fellow99.rpauto.proj

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import top.fellow99.rpauto.core.controller.AppScreenController
import top.fellow99.rpauto.core.controller.DisplayMode
import top.fellow99.rpauto.core.controller.VdSessionImpl
import top.fellow99.rpauto.core.shell.NoRootShell
import top.fellow99.rpauto.core.server.MfaLog

/**
 * [DisplayMigrator] —— 承载模式切换时的「本应用 ↔ 承载应用」跨屏迁移（010-display-mode §8.2 本应用搬屏）。
 *
 * 拓扑翻转（真机探针已验证，shell UID 可经 `am start --display` 调度到 owner=shell 的 PRIVATE 虚拟屏）：
 *
 * - 进入主屏承载（Mode B）：
 *   1) 本应用 MainActivity 调度到**虚拟屏**（用户主屏不再看到配置界面）；
 *   2) 承载应用调度到**主屏 displayId=0**（前置到用户主屏）。
 * - 切回虚拟屏承载（Mode A）：
 *   1) 承载应用 `force-stop` 后重新 `am start --display <vd>` 回**虚拟屏**（force-stop 规避 task 残留复用，§8.3-1）；
 *   2) 本应用 MainActivity 调度回**主屏**。
 *
 * 注意：MediaProjection 系统授权弹窗必须在本应用仍在主屏、用户在场时完成；
 * 因此调用方应**先完成授权（onGranted）再调用 [enterMain] 做搬屏**。
 */
class DisplayMigrator(
    private val context: Context,
    private val shell: NoRootShell,
) {

    private val pkg: String get() = context.packageName

    /** 当前会话的私有虚拟屏 displayId；无会话返回 null。 */
    private fun virtualDisplayId(controller: AppScreenController): Int? =
        (controller.vdSession as? VdSessionImpl)?.virtualDisplayId()

    /**
     * 进入主屏承载：本应用→虚拟屏；承载应用→主屏。
     * 应在 MediaProjection 授权完成、Mode 已切 MAIN 后调用。
     */
    fun enterMain(controller: AppScreenController) {
        val hosted = controller.hosted()?.packageName
        // 1) 承载应用前置到主屏（复用 ModeBOps 的主屏 launch，保证 task 置顶）。
        if (hosted != null) {
            runCatching { controller.mainOps?.launch(hosted, null) }
                .onSuccess { MfaLog.i("Migrator", "hosted $hosted -> main display=0") }
                .onFailure { MfaLog.w("Migrator", "hosted app -> main failed: ${it.message}") }
        }
        // 2) 本应用搬到虚拟屏（用户主屏让出给承载应用）。
        val vd = virtualDisplayId(controller)
        if (vd != null) moveOwnActivityToDisplay(vd)
        else MfaLog.w("Migrator", "no virtual display id; keep own app on main")
    }

    /**
     * 切回虚拟屏承载：承载应用→虚拟屏；本应用→主屏。
     */
    fun enterVirtual(controller: AppScreenController) {
        val vd = virtualDisplayId(controller)
        val hosted = controller.hosted()?.packageName
        // 1) 承载应用回虚拟屏：先 force-stop 规避 task 残留（§8.3-1），再 am start --display。
        if (hosted != null && vd != null) {
            runCatching {
                val comp = launcherComponent(hosted)
                shellExec("am force-stop $hosted")
                val out = shellExec("am start --display $vd -n $comp")
                MfaLog.i("Migrator", "hosted $hosted -> virtual display=$vd out=${out?.take(60)}")
            }.onFailure { MfaLog.w("Migrator", "hosted app -> virtual failed: ${it.message}") }
        }
        // 2) 本应用回主屏。
        moveOwnActivityToDisplay(0)
    }

    /** 经 shell `am start --display` 把本应用 MainActivity 调度到指定 display。 */
    private fun moveOwnActivityToDisplay(displayId: Int) {
        val out = shellExec("am start --display $displayId -n $pkg/.MainActivity")
        val err = shell.lastError?.message
        MfaLog.i("Migrator", "move own MainActivity -> display=$displayId out=${out?.take(60)} lastError=$err")
    }

    /** 解析承载应用的启动组件（package/activity）；解析不到回退 package（am 自行解析 launcher）。 */
    private fun launcherComponent(hostedPkg: String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(hostedPkg)
        val comp = intent?.component
        return comp?.let { "${it.packageName}/${it.className}" } ?: hostedPkg
    }

    private fun shellExec(command: String): String? = try {
        runBlocking { shell.run(command) }
    } catch (e: Exception) {
        MfaLog.w("Migrator", "shellExec failed: ${e.message}")
        null
    }
}
