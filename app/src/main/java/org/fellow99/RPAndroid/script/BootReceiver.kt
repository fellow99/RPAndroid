package org.fellow99.RPAndroid.script

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fellow99.RPAndroid.ManagedRuntime
import org.fellow99.RPAndroid.core.server.MfaLog

/**
 * [BootReceiver] —— 开机 / 应用被覆盖安装后恢复调度。
 *
 * 不恢复的话：AlarmManager 的闹钟在重启或覆盖安装后全部丢失，已启用的 cron/interval 任务
 * 会静默失效（真机回归发现问题）。这里幂等地拉起运行时并重排所有已启用任务的闹钟。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        runCatching { ManagedRuntime.start(app) }
            .onFailure { MfaLog.w("Script", "boot start failed: ${it.message}") }
        runCatching {
            TaskScheduler(app).rescheduleAll()
            MfaLog.i("Script", "boot reschedule done action=$action")
        }
    }
}
