package com.managedfloating.core.server

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
}
