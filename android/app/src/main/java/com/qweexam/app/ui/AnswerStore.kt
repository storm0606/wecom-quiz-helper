package com.qweexam.app.ui

import com.qweexam.app.service.MatchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 答案结果桥：无障碍服务（M3）写入，悬浮窗（M4）订阅显示。
 */
object AnswerStore {
    val serviceConnected = MutableStateFlow(false)
    val overlayRunning = MutableStateFlow(false)

    private val _lastResult = MutableStateFlow<MatchResult?>(null)
    val lastResult: StateFlow<MatchResult?> = _lastResult

    /** 无障碍服务发布匹配结果（悬浮窗订阅显示） */
    fun publish(result: MatchResult) {
        _lastResult.value = result
    }
}
