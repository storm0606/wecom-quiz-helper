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
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 显示当前激活题库：所有题库 / 指定题库名称（v1.3） */
    private fun refreshActiveBank() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                val db = AppDatabase.get(this@MainActivity)
                if (answerRepository.matchMode() == "all") {
                    getString(R.string.active_bank_all, db.questionDao().getAll().size)
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
        // 3) 启动悬浮窗
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(Intent(this, AnswerOverlayService::class.java))
        } else {
            startService(Intent(this, AnswerOverlayService::class.java))
        }
        binding.btnQuiz.text = getString(R.string.btn_stop_quiz)
    }

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
