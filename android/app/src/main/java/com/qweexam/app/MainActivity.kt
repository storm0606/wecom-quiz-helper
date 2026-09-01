package com.qweexam.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qweexam.app.databinding.ActivityMainBinding
import com.qweexam.app.data.db.AppDatabase
import com.qweexam.app.data.repository.AnswerRepository
import com.qweexam.app.service.AnswerOverlayService
import com.qweexam.app.ui.AnswerStore
import com.qweexam.app.ui.BankListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 企微考试 - 主界面
 *
 * 三大入口：题库管理 / 开始答题（悬浮窗）/ 设置（无障碍设置页）。
 * 首页显示：版本、无障碍状态、当前激活题库（v1.3）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var answerRepository: AnswerRepository

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不阻塞，前台服务通知可能不显示 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        answerRepository = AnswerRepository(AppDatabase.get(this))

        binding.tvVersion.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)

        binding.btnBank.setOnClickListener {
            startActivity(Intent(this, BankListActivity::class.java))
        }
        binding.btnQuiz.setOnClickListener { toggleOverlay() }
        binding.btnOverlaySettings.setOnClickListener { showOverlaySettingsDialog() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 悬浮窗运行状态 → 按钮文字（开始/停止），实时联动
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AnswerStore.overlayRunning.collect { running ->
                    binding.btnQuiz.text = getString(
                        if (running) R.string.btn_stop_quiz else R.string.btn_start_quiz
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.tvServiceStatus.text = if (isAccessibilityEnabled()) {
            getString(R.string.service_status_on)
        } else {
            getString(R.string.service_status_off)
        }
        binding.btnQuiz.text = getString(
            if (AnswerStore.overlayRunning.value) R.string.btn_stop_quiz else R.string.btn_start_quiz
        )
        refreshActiveBank()
    }

    /** 显示当前激活题库：所有题库 / 指定题库名称（v1.3；v2.0 改 COUNT 查询避免全表加载） */
    private fun refreshActiveBank() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                val db = AppDatabase.get(this@MainActivity)
                if (answerRepository.matchMode() == "all") {
                    getString(R.string.active_bank_all, db.questionDao().countAll())
                } else {
                    val bankId = answerRepository.currentBankId()
                    if (bankId == null) {
                        getString(R.string.active_bank_none)
                    } else {
                        val bank = db.bankDao().getById(bankId)
                        val count = db.questionDao().countByBank(bankId)
                        getString(
                            R.string.active_bank_specific,
                            bank?.name ?: "题库#$bankId",
                            count
                        )
                    }
                }
            }
            binding.tvActiveBank.text = getString(R.string.active_bank_label, info)
        }
    }

    private fun toggleOverlay() {
        if (AnswerStore.overlayRunning.value) {
            stopService(Intent(this, AnswerOverlayService::class.java))
            return
        }
        // 1) 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, R.string.overlay_permission_hint, Toast.LENGTH_LONG).show()
            return
        }
        // 2) 无障碍权限
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, R.string.accessibility_not_enabled_hint, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        // 3) 通知权限（Android 13+，仅此刻请求一次，避免每次回首页弹窗造成卡顿）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 4) 启动悬浮窗
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(Intent(this, AnswerOverlayService::class.java))
        } else {
            startService(Intent(this, AnswerOverlayService::class.java))
        }
        binding.btnQuiz.text = getString(R.string.btn_stop_quiz)
    }

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 悬浮窗自定义设置弹窗（v1.5/v1.6）：反馈方式 + 多选轮换色板 */
    private fun showOverlaySettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.overlay_settings_dialog, null)
        val rg = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgFeedback)
        val swatchContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.swatchContainer)

        // 预设色板（多选）
        val presets = listOf(
            "默认蓝" to AnswerRepository.DEFAULT_OVERLAY_BG,
            "深蓝" to "#E6174EA6",
            "绿色" to "#E60FA958",
            "橙色" to "#E6E8710A",
            "紫色" to "#E67B1FA2",
            "红色" to "#E6C62828",
            "深灰" to "#E6374A5B"
        )
        val selected = mutableListOf<String>()

        lifecycleScope.launch {
            val (feedback, colorsHex) = withContext(Dispatchers.IO) {
                val f = answerRepository.overlayFeedback()
                val hexList = answerRepository.overlayColors().map { "#%08X".format(it) }
                f to hexList
            }
            when (feedback) {
                "none" -> rg.check(R.id.rbFeedbackNone)
                "color" -> rg.check(R.id.rbFeedbackColor)
                else -> rg.check(R.id.rbFeedbackFlash)
            }
            selected.clear()
            selected.addAll(colorsHex)

            presets.forEach { (name, hex) ->
                val swatch = android.widget.FrameLayout(this@MainActivity).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        dp(44), dp(44)
                    ).apply { marginEnd = dp(10) }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(android.graphics.Color.parseColor(hex))
                    }
                    isClickable = true
                    contentDescription = name
                    setOnClickListener {
                        if (selected.contains(hex)) selected.remove(hex) else selected.add(hex)
                        refreshSwatchBorders(swatchContainer, presets, selected)
                    }
                }
                swatchContainer.addView(swatch)
            }
            refreshSwatchBorders(swatchContainer, presets, selected)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.overlay_settings_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mode = when (rg.checkedRadioButtonId) {
                    R.id.rbFeedbackNone -> "none"
                    R.id.rbFeedbackColor -> "color"
                    else -> "flash"
                }
                if (mode == "color" && selected.size < 2) {
                    showToast(getString(R.string.overlay_colors_min))
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        answerRepository.setOverlayFeedback(mode)
                        answerRepository.setOverlayColors(selected)
                    }
                    dialog.dismiss()
                    showToast(getString(R.string.overlay_settings_saved))
                }
            }
        }
        dialog.show()
    }

    private fun refreshSwatchBorders(
        container: android.widget.LinearLayout,
        presets: List<Pair<String, String>>,
        selected: List<String>
    ) {
        for (i in 0 until container.childCount) {
            val swatch = container.getChildAt(i)
            val bg = swatch.background as android.graphics.drawable.GradientDrawable
            if (selected.contains(presets[i].second)) {
                bg.setStroke(dp(3), ContextCompat.getColor(this, R.color.qwe_primary))
            } else {
                bg.setStroke(dp(1), 0x33000000)
            }
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // 系统存储的是完整组件名（com.qweexam.app/com.qweexam.app.service.QuizAccessibilityService），
        // 匹配类名即可（不同 ROM 前缀格式可能不同）
        return enabled.contains(".service.QuizAccessibilityService")
    }
}
