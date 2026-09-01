package com.qweexam.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.qweexam.app.R
import com.qweexam.app.ui.AnswerStore
import com.qweexam.app.ui.OverlayTextFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 悬浮窗服务（M4）：订阅 AnswerStore（M3 匹配结果），悬浮窗实时显示答案。
 * 支持拖动、收起/展开、关闭；前台服务（specialUse）常驻。
 */
class AnswerOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var repo: com.qweexam.app.data.repository.AnswerRepository? = null

    /** 缩放（v1.1：双指捏合，0.8x–2.5x，多选题内容多时可放大） */
    private var scaleFactor = 1.0f
    private var scaleDetector: ScaleGestureDetector? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null
    private var firstUpdate = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repo = com.qweexam.app.data.repository.AnswerRepository(com.qweexam.app.data.db.AppDatabase.get(this))
        showOverlay()
        collectJob = scope.launch {
            AnswerStore.lastResult.collectLatest { result ->
                updateOverlay(result)
            }
        }
        AnswerStore.overlayRunning.value = true
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
        AnswerStore.overlayRunning.value = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .build()
    }

    private fun showOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_answer, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 16
        lp.y = 200
        try {
            windowManager.addView(view, lp)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        overlayView = view
        params = lp

        view.findViewById<TextView>(R.id.btnClose).setOnClickListener { stopSelf() }
        view.findViewById<TextView>(R.id.btnCollapse).setOnClickListener {
            collapsed = !collapsed
            val tvAnswer = view.findViewById<TextView>(R.id.tvAnswer)
            val tvMeta = view.findViewById<TextView>(R.id.tvMeta)
            tvAnswer.visibility = if (collapsed) View.GONE else View.VISIBLE
            tvMeta.visibility = if (collapsed) View.GONE else View.VISIBLE
            view.findViewById<TextView>(R.id.btnCollapse).text =
                getString(if (collapsed) R.string.overlay_expand else R.string.overlay_collapse)
        }

        // 双指缩放
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.8f, 2.5f)
                applyScale(view)
                return true
            }
        })

        // 拖动（单指）；拖动位置钳制在屏幕内，避免缩放到屏幕外
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        view.setOnTouchListener { v, event ->
            scaleDetector?.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> true
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaleDetector?.isInProgress == true) {
                        true
                    } else {
                        var nx = initialX + (event.rawX - touchStartX).toInt()
                        var ny = initialY + (event.rawY - touchStartY).toInt()
                        val w = if (v.width > 0) v.width else 200
                        val h = if (v.height > 0) v.height else 200
                        nx = nx.coerceIn(0, (screenW - w).coerceAtLeast(0))
                        ny = ny.coerceIn(0, (screenH - h).coerceAtLeast(0))
                        lp.x = nx
                        lp.y = ny
                        runCatching { windowManager.updateViewLayout(v, lp) }
                        true
                    }
                }
                else -> false
            }
        }

        // 首次显示（读设置应用背景色）
        scope.launch { updateOverlay(AnswerStore.lastResult.value) }
        applyScale(view)
    }

    private suspend fun updateOverlay(result: com.qweexam.app.service.MatchResult?) {
        val view = overlayView ?: return
        // 读取用户自定义设置（反馈方式 + 背景色）
        val feedback = repo?.overlayFeedback() ?: "flash"
        val bgColor = repo?.overlayBgColor() ?: android.graphics.Color.parseColor(
            com.qweexam.app.data.repository.AnswerRepository.DEFAULT_OVERLAY_BG
        )
        applyBgColor(view, bgColor)

        val (answer, meta) = OverlayTextFormatter.format(result)
        view.findViewById<TextView>(R.id.tvAnswer).text = answer
        view.findViewById<TextView>(R.id.tvMeta).text = meta
        // 识别到新题（非首次显示）→ 按用户设置的反馈方式提示（v1.4/v1.5）
        if (result != null && !firstUpdate) {
            when (feedback) {
                "flash" -> flashOverlay(view)
                "color" -> flashColorOverlay(view, bgColor)
                else -> { /* 无反馈 */ }
            }
        }
        firstUpdate = false
    }

    /** 应用自定义背景色（覆盖 overlay_bg 的 solid 颜色） */
    private fun applyBgColor(view: View, color: Int) {
        val bg = view.background as? android.graphics.drawable.GradientDrawable ?: return
        bg.setColor(color)
    }

    /** 反馈-高亮闪烁：窗口亮度快速下降再恢复（约 0.4s） */
    private fun flashOverlay(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.animate().alpha(0.35f).setDuration(120).withEndAction {
            view.animate().alpha(1f).setDuration(260).start()
        }.start()
    }

    /** 反馈-背景色短暂变化：背景闪成浅色再恢复自定义背景色（约 0.4s） */
    private fun flashColorOverlay(view: View, normalColor: Int) {
        val bg = view.background as? android.graphics.drawable.GradientDrawable ?: return
        val highlight = 0xFFFFE082.toInt() // 浅琥珀色
        bg.setColor(highlight)
        view.animate().cancel()
        view.animate().alpha(1f).setDuration(0).start()
        view.postDelayed({
            if (overlayView === view) {
                bg.setColor(normalColor)
            }
        }, 380)
    }

    /** 按缩放系数调整悬浮窗内文字大小（答案区限高 160dp，内容超长时内部滚动，不溢出屏幕） */
    private fun applyScale(view: View) {
        view.findViewById<TextView>(R.id.tvAnswer)?.let {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scaleFactor)
            it.movementMethod = android.text.method.ScrollingMovementMethod()
            it.isVerticalScrollBarEnabled = true
        }
        view.findViewById<TextView>(R.id.tvTitle)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scaleFactor)
        view.findViewById<TextView>(R.id.tvMeta)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * scaleFactor)
        view.findViewById<TextView>(R.id.btnCollapse)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scaleFactor)
        view.findViewById<TextView>(R.id.btnClose)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scaleFactor)
    }
}
