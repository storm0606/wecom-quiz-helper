package com.qweexam.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qweexam.app.MainActivity
import com.qweexam.app.R
import com.qweexam.app.data.db.AppDatabase
import com.qweexam.app.data.repository.AuthRepository
import com.qweexam.app.databinding.ActivityAuthBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2FA 设备鉴权（v1.2）：应用启动入口。
 * - 已绑定当前设备 → 直接进入主界面；
 * - 未绑定 → 输入验证器 6 位动态码 → 校验通过 → 存储设备指纹 → 进入主界面。
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var repository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AuthRepository(AppDatabase.get(this))

        binding.btnVerify.setOnClickListener { doVerify() }

        // 已绑定则直接放行；否则按是否已存密钥决定显示模式
        lifecycleScope.launch {
            val (bound, hasSecret) = withContext(Dispatchers.IO) {
                repository.isBound(this@AuthActivity) to repository.hasSecret()
            }
            if (bound) {
                enterMain()
            } else {
                binding.secretLayout.visibility =
                    if (hasSecret) android.view.View.GONE else android.view.View.VISIBLE
                binding.tvHint.text = getString(
                    if (hasSecret) R.string.auth_hint else R.string.auth_hint_setup
                )
            }
        }
    }

    private fun doVerify() {
        val code = binding.etCode.text.toString().trim()
        if (code.length != 6) {
            Toast.makeText(this, R.string.auth_code_format_hint, Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnVerify.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                // 首次：先保存用户输入的密钥，再校验验证码
                if (!repository.hasSecret()) {
                    val secret = binding.etSecret.text.toString().trim()
                    if (secret.isNotEmpty()) {
                        repository.saveSecret(secret)
                    }
                }
                repository.verifyAndBind(this@AuthActivity, code)
            }
            binding.btnVerify.isEnabled = true
            if (ok) {
                Toast.makeText(this@AuthActivity, R.string.auth_success, Toast.LENGTH_SHORT).show()
                enterMain()
            } else {
                Toast.makeText(this@AuthActivity, R.string.auth_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enterMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
