package com.managedfloating.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.managedfloating.app.present.FloatingService
import com.managedfloating.app.present.ScreenActivity
import com.managedfloating.core.server.ServerConfig
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 配置界面：按编号步骤引导（建会话 → 选 App → 选呈现模式 → 可选安全/无障碍）。
 * 顶部「服务运行中」为可点击链接，弹出 API / MCP / MJPEG 接入信息。
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

        // 顶部状态：可点击链接，弹出接入信息
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 12, 0, 16)
            setTextColor(Color.parseColor("#1565C0"))
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { showConnectionDialog() }
        }
        root.addView(status)
        refreshStatus("服务运行中 · 点此查看 API / MCP / MJPEG 接入信息")

        // —— 步骤 1：建立会话 ——
        root.addView(stepHeader("1", "连接并建立会话（必做）"))
        portInput = EditText(this).apply {
            hint = "adbd 连接端口（如 37577）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(portInput)
        root.addView(Button(this).apply {
            text = "连接并建立会话"
            setOnClickListener { doStartSession() }
        })

        // —— 步骤 2：选择被托管 App ——
        root.addView(stepHeader("2", "选择要托管的 App（需先完成步骤 1）"))
        root.addView(Button(this).apply {
            text = "刷新已安装 App 列表"
            setOnClickListener { loadApps() }
        })
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(appList)

        // —— 步骤 3：选择呈现模式 ——
        root.addView(stepHeader("3", "选择呈现模式"))
        root.addView(Button(this).apply {
            text = "常规模式（全屏显示/操作）"
            setOnClickListener { startActivity(Intent(this@MainActivity, ScreenActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "浮窗模式（悬浮窗显示/操作）"
            setOnClickListener { doFloating() }
        })
        root.addView(Button(this).apply {
            text = "Headless（不渲染，只走 REST/MCP/视频流）"
            setOnClickListener {
                FloatingService.stop(this@MainActivity)
                Toast.makeText(this@MainActivity, "已切换 headless", Toast.LENGTH_SHORT).show()
            }
        })

        // —— 步骤 4：安全 token（可选） ——
        root.addView(stepHeader("4", "安全 token（可选，建议设置）"))
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

        // —— 步骤 5：无障碍（可选） ——
        root.addView(stepHeader("5", "无障碍服务（可选，用于 ui_read 读界面）"))
        root.addView(Button(this).apply {
            text = "启用无障碍服务"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this@MainActivity, "请在列表中启用 ManagedFloatingApp", Toast.LENGTH_LONG).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        requestNotificationPermission()
        loadApps()
    }

    /** 带编号圆点的步骤标题。 */
    private fun stepHeader(no: String, title: String): TextView = TextView(this).apply {
        text = "步骤 $no  ·  $title"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#37474F"))
        setPadding(0, 28, 0, 10)
    }

    private fun refreshStatus(label: String) {
        val s = SpannableString(label)
        s.setSpan(UnderlineSpan(), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        status.text = s
    }

    /** 弹出 API / MCP / MJPEG 接入信息。 */
    private fun showConnectionDialog() {
        val port = ServerConfig(this).port
        val ip = lanIp()
        val token = ServerConfig(this).token.ifBlank { "<未设置，无需鉴权>" }
        val base = "http://$ip:$port"
        val local = "http://localhost:$port"

        val msg = """
            ▶ API 端点访问 URL（REST）
              局域网 Base：$base
              USB(adb forward)：$local
              鉴权头：X-Auth-Token: <token>
              查询兜底：?token=<token>
              示例：
                GET  $base/status
                GET  $base/apps
                GET  $base/frame
                POST $base/action/tap   {"x":360,"y":500}
              当前 token：$token

            ▶ MCP 的连接方式
              A. Streamable HTTP（单次请求/响应）：
                 POST $base/mcp
                 Header: Content-Type: application/json
                 Body: {"jsonrpc":"2.0","id":1,"method":"tools/list"}
              B. SSE 长连接：
                 GET  $base/mcp/sse
                 → 收到 event: endpoint，data: /mcp?sessionId=<id>
                 → POST $base/mcp?sessionId=<id> 发 JSON-RPC
                 → 结果经 SSE 的 event: message 推回
              工具：screen_capture / ui_read / screen_tap /
                    screen_swipe / app_list / app_launch / task_*

            ▶ MJPEG 视频流访问方式
              浏览器直接打开：
                $base/stream?token=<token>
              curl：
                curl -H "X-Auth-Token: <token>" $base/stream -o stream.mjpeg
              HTML 内嵌：
                <img src="$base/stream?token=<token>" />
              响应：multipart/x-mixed-replace; boundary=frame
              （最多 8 路并发，超出返回 503）
        """.trimIndent()

        val tv = TextView(this).apply {
            text = msg
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(48, 32, 48, 16)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("接入信息")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 取第一个非回环 IPv4 地址（局域网 IP）。 */
    private fun lanIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "127.0.0.1"
    }.getOrDefault("127.0.0.1")

    /** Android 13+ 前台服务通知需运行时授权，否则通知不展示（服务本身仍可运行）。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS,
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (granted) "通知权限已授予" else "未授予通知权限（不影响核心功能）",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 1001
    }

    private fun doStartSession() {
        val port = portInput.text.toString().trim().toIntOrNull()
        refreshStatus("正在连接 adbd 并拉起助手…")
        Thread {
            val ok = ManagedRuntime.startSession(port)
            runOnUiThread {
                if (ok) {
                    refreshStatus("会话已就绪 · 点此查看接入信息")
                    Toast.makeText(this, "会话已就绪，请继续步骤 2", Toast.LENGTH_SHORT).show()
                } else {
                    refreshStatus("会话启动失败（检查端口/配对）· 点此查看接入信息")
                }
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
                                if (r.ok) "已承载 ${a.label}，可到步骤 3 选呈现模式"
                                else "承载失败：${r.message}（请确认已完成步骤 1）",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }.start()
                }
            })
        }
    }
}
