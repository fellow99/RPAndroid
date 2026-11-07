package com.managedfloating.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.managedfloating.app.present.FloatingService
import com.managedfloating.app.present.ScreenActivity
import com.managedfloating.core.server.ServerConfig

/**
 * 配置界面（M2）：启动会话、选择被托管 App、切换三种呈现模式、配置 token/端口。
 * 纯 View 实现（AGP9 内置 Kotlin 下最稳）。
 */
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var portInput: EditText
    private lateinit var appList: LinearLayout
    private lateinit var tokenInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ManagedRuntime.start(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "ManagedFloatingApp"
            textSize = 22f
        })

        status = TextView(this).apply {
            textSize = 13f
            text = "状态：服务运行中（端口 ${ManagedRuntime.server?.let { "" } ?: ""}）"
            setPadding(0, 12, 0, 12)
        }
        root.addView(status)

        portInput = EditText(this).apply {
            hint = "adbd 连接端口（如 37577）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(portInput)

        root.addView(Button(this).apply {
            text = "连接并建立会话"
            setOnClickListener { doStartSession() }
        })

        root.addView(Button(this).apply {
            text = "刷新已安装 App"
            setOnClickListener { loadApps() }
        })

        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(appList)

        root.addView(TextView(this).apply { text = "\n呈现模式"; textSize = 16f; setPadding(0, 16, 0, 8) })
        root.addView(Button(this).apply {
            text = "常规模式（全屏显示/操作）"
            setOnClickListener { startActivity(Intent(this@MainActivity, ScreenActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "浮窗模式（悬浮窗显示/操作）"
            setOnClickListener { doFloating() }
        })
        root.addView(Button(this).apply {
            text = "Headless（不渲染，只走 REST）"
            setOnClickListener {
                FloatingService.stop(this@MainActivity)
                Toast.makeText(this@MainActivity, "已切换 headless", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(TextView(this).apply { text = "\n安全 token（可选）"; textSize = 16f; setPadding(0, 16, 0, 8) })
        tokenInput = EditText(this).apply {
            hint = "留空表示不校验"
            setText(ServerConfig(this@MainActivity).token)
        }
        root.addView(tokenInput)
        root.addView(Button(this).apply {
            text = "保存 token（重启 App 生效）"
            setOnClickListener {
                ServerConfig(this@MainActivity).token = tokenInput.text.toString().trim()
                Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        loadApps()
    }

    private fun doStartSession() {
        val port = portInput.text.toString().trim().toIntOrNull()
        status.text = "正在连接 adbd 并拉起助手…"
        Thread {
            val ok = ManagedRuntime.startSession(port)
            runOnUiThread {
                status.text = if (ok) "会话已就绪：可用常规/浮窗模式，或 REST" else "会话启动失败（检查端口/配对）"
            }
        }.start()
    }

    private fun doFloating() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "请授予“显示在其他应用上层”，再点浮窗模式", Toast.LENGTH_LONG).show()
            return
        }
        FloatingService.start(this)
        Toast.makeText(this, "浮窗已启动", Toast.LENGTH_SHORT).show()
    }

    private fun loadApps() {
        appList.removeAllViews()
        val controller = ManagedRuntime.controller() ?: return
        val apps = runCatching { controller.listApps() }.getOrElse { emptyList() }
        for (a in apps) {
            appList.addView(Button(this).apply {
                text = "${a.label}  (${a.packageName})"
                textSize = 12f
                setOnClickListener {
                    Thread {
                        val r = controller.launch(a.packageName, null)
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                if (r.ok) "已承载 ${a.label}" else "承载失败：${r.message}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }.start()
                }
            })
        }
    }
}
