package com.managedfloating.core.server

/**
 * 任务 API 回调（由 app 模块实现，桥接 TaskStore + TaskScheduler + :node 执行器）。
 */
interface TaskApi {
    fun listJson(): String
    fun upsert(taskJson: String): Boolean
    fun delete(id: String): Boolean
    fun run(id: String): Boolean
}
