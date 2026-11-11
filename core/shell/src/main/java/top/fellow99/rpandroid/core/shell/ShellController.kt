package top.fellow99.rpandroid.core.shell

/**
 * [ShellController] —— 免 root 托管基座（架构 A）占位。
 *
 * 后续里程碑实现：
 *  - 无线调试配对 / root 直连，启动 shell 助手进程（shell UID）
 *  - 创建 VirtualDisplay 虚拟屏并接管其生命周期
 *  - 把目标 App 启动 / 挪入虚拟屏（startActivity --display / WindowManager）
 *  - 输入注入（input tap/swipe/key / IWindowManager）
 *  - 方向 / 焦点处理（Android 14+ 完整，低版本降级）
 *  - 把虚拟屏的 Surface 交给上层（controller）直接接收帧
 */
interface ShellController {
    fun isReady(): Boolean
}
