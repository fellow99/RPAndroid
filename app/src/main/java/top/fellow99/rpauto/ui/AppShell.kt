package top.fellow99.rpauto.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import top.fellow99.rpauto.present.FloatingService
import top.fellow99.rpauto.present.ManagedBridge

/** 底部栏目。 */
enum class Tab(val label: String, val glyph: String) {
    Apps("应用", "▦"),
    Automation("自动化", "⏱"),
    Settings("设置", "⚙"),
}

/**
 * 主界面骨架：底部 NavigationBar（应用/自动化/设置）+ 内容区 + 系统返回键转发。
 *
 * 返回键规则（FR-101-9）：
 *  - 浮窗激活（焦点在浮窗）→ 转发给被托管 App；
 *  - 焦点在本 App 且栏目在「应用」且已承载应用 → 转发给被托管 App；
 *  - 其余（设置/自动化栏目，或未承载）→ 常规处理（退出）。
 */
@Composable
fun AppShell(onFinish: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Apps) }
    val context = LocalContext.current

    // Android 13+ 前台服务通知静态授权（若未授予则弹出一次请求）。
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = true) {
        val sessionOk = ManagedBridge.controller()?.isSessionActive() == true
        val floating = FloatingService.isActive
        val forward = (tab == Tab.Apps && PresentationHub.hostedApp != null) || floating
        if (forward && sessionOk) {
            val ctl = ManagedBridge.controller()
            if (ctl != null) Thread { runCatching { ctl.back() } }.start()
        } else {
            onFinish()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.glyph) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Apps -> AppsScreen(onOpenSettings = { tab = Tab.Settings })
                Tab.Automation -> AutomationScreen(onOpenSettings = { tab = Tab.Settings })
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}
