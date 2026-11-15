package top.fellow99.rpauto.core.script

/**
 * [ScriptExecutorService] —— **自动化服务**任务执行器（架构 E）占位。
 *（符号名沿用历史 Script*，产品术语统一为「自动化服务」。）
 *
 * 后续里程碑实现：
 *  - 前台 Service：任务编排与状态机
 *  - 调度：立即 / 间隔 / cron（AlarmManager setExactAndAllowWhileIdle + 掌上调度）
 *  - JS 执行器：基于 aosp-node-bridge 的 [io.github.fellow99.nodebridge.NodeRuntime]
 *      在独立 :node 进程运行（崩溃隔离 + 可重启）
 *  - 宿主能力（host.register）经 Binder 代理调用主进程 ScreenController
 *
 * 依赖已引入：
 *  - io.github.fellow99:node-bridge:0.2.0（嵌入 API）
 *  - io.github.fellow99:node-runtime:26.13.0（引擎 libnode.so，runtimeOnly，arm64-only）
 */
interface ScriptExecutorService {
    fun isRunning(): Boolean
}
