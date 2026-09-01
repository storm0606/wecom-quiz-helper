package com.qweexam.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.qweexam.app.BuildConfig
import com.qweexam.app.data.db.AppDatabase
import com.qweexam.app.data.parser.Normalizer
import com.qweexam.app.data.repository.AnswerRepository
import com.qweexam.app.ui.AnswerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无障碍读题服务：监听企业微信（com.tencent.wework）答题页，
 * 提取题干 → 匹配题库 → 结果写入 AnswerStore（M4 悬浮窗订阅显示）。
 */
class QuizAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "QWeExam"
        private const val PACKAGE_WEWORK = "com.tencent.wework"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var repo: AnswerRepository? = null
    private var lastStemNorm = ""
    private var lastEventAt = 0L

    private val debounce = Runnable { handleScreen() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repo = AnswerRepository(AppDatabase.get(this))
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "无障碍服务已连接（监听 $PACKAGE_WEWORK）")
        }
        AnswerStore.serviceConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != PACKAGE_WEWORK) return
        // 限频：切题时事件密集，至少间隔 400ms 才重新调度
        val now = System.currentTimeMillis()
        if (now - lastEventAt < 400) return
        lastEventAt = now
        handler.removeCallbacks(debounce)
        handler.postDelayed(debounce, 500)
    }

    override fun onInterrupt() {
        // 系统中断无障碍时调用
    }

    override fun onDestroy() {
        handler.removeCallbacks(debounce)
        scope.cancel()
        super.onDestroy()
    }

    private fun handleScreen() {
        val root = rootInActiveWindow ?: return
        val nodes = mutableListOf<ScreenNode>()
        collectNodes(root, nodes)
        val blocks = ScreenQuestionExtractor.findQuizBlocks(nodes)
        if (blocks.isEmpty()) return
        val block = blocks.first()
        val stemNorm = Normalizer.normalize(block.stem)
        if (stemNorm.isEmpty() || stemNorm == lastStemNorm) return
        lastStemNorm = stemNorm
        scope.launch {
            // 匹配（含模糊 LCS）放到后台线程，避免大题库合并时卡主线程（v1.7 修复）
            val result = withContext(Dispatchers.Default) { repo?.match(block.stem) }
                ?: return@launch
            val finalResult = ScreenAnswerFallback.merge(result, block)
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "题干[${block.header}]: ${block.stem.take(24)}... → ${finalResult.display} [${finalResult.status}]")
            }
            AnswerStore.publish(finalResult)
        }
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, out: MutableList<ScreenNode>) {
        if (node == null) return
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                out.add(ScreenNode(text, rect))
            }
        }
        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), out)
        }
    }
}
