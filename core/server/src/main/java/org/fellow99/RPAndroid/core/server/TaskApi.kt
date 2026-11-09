package org.fellow99.RPAndroid.core.server

/**
 * 任务 API 回调（由 app 模块实现，桥接 TaskStore + TaskScheduler + :node 执行器）。
 */
interface TaskApi {
    fun listJson(): String
    fun upsert(taskJson: String): Boolean

    /** 部分更新已存在的任务（仅可编辑字段，保留运行统计）；任务不存在返回 false。 */
    fun modify(id: String, patchJson: String): Boolean
    fun delete(id: String): Boolean
    fun run(id: String): Boolean

    /** 停止自动化（取消调度 + 中断当前执行）。 */
    fun stop(id: String): Boolean

    /** 单任务 JSON（含运行统计）；不存在返回 null。 */
    fun getJson(id: String): String?

    /** 等待某任务执行结束（或超时）；返回是否已不再处于运行中。 */
    fun waitRun(id: String, timeoutMs: Long): Boolean
}
