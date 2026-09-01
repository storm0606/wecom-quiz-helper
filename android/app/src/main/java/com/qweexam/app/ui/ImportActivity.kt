package com.qweexam.app.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.qweexam.app.R
import com.qweexam.app.data.db.AppDatabase
import com.qweexam.app.data.parser.BankParser
import com.qweexam.app.data.repository.BankRepository
import com.qweexam.app.databinding.ActivityImportBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 题库导入：文件选择 → 解析预览 → 手动列映射 → 入库
 * （v1.0：.xls 多 sheet 暂取第一个，选 sheet 功能在 v1.1）
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding
    private lateinit var repository: BankRepository
    private lateinit var adapter: ColumnMappingAdapter

    private var fileName: String = ""
    private var fileData: ByteArray? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadAndShow(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setTitle(R.string.module_bank)

        repository = BankRepository(AppDatabase.get(this))
        adapter = ColumnMappingAdapter(mutableListOf())
        binding.rvColumns.layoutManager = LinearLayoutManager(this)
        binding.rvColumns.adapter = adapter

        binding.btnPickFile.setOnClickListener {
            pickFile.launch(
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // xlsx
                    "application/vnd.ms-excel",                                          // xls
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // docx
                    "text/csv", "text/comma-separated-values", "text/plain",             // csv
                    "*/*"
                )
            )
        }
        binding.btnImport.setOnClickListener { doImport() }
    }

    private fun loadAndShow(uri: Uri) {
        lifecycleScope.launch {
            try {
                val (name, data) = withContext(Dispatchers.IO) {
                    val n = queryName(uri)
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取文件")
                    n to bytes
                }
                fileName = name
                fileData = data
                binding.tvFileName.text = name
                binding.btnImport.isEnabled = true
                binding.tvResult.text = ""

                withContext(Dispatchers.IO) {
                    val rows = BankParser.parseRows(name, data)
                    val roles = BankParser.autoColumnRoles(rows)
                    Log.i(TAG, "解析: $name 行数=${rows.size} 自动角色=$roles")
                    if (rows.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            binding.tvResult.text = getString(R.string.import_parse_failed)
                        }
                        return@withContext
                    }
                    withContext(Dispatchers.Main) {
                        adapter.items.clear()
                        adapter.items.addAll(roles)
                        adapter.notifyDataSetChanged()
                        // 预览前 3 行
                        binding.tvPreview.text = rows.take(3).joinToString("\n") { row ->
                            row.joinToString(" | ") { it.ifEmpty { "—" } }
                        }
                    }
                }
            } catch (e: Exception) {
                binding.tvResult.text = "解析失败：${e.message}"
            }
        }
    }

    private fun doImport() {
        val data = fileData ?: return
        val roles = adapter.collectRoles()
        val mapping = try {
            adapter.buildMapping(roles)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnImport.isEnabled = false
        lifecycleScope.launch {
            try {
                val (_, count) = withContext(Dispatchers.IO) {
                    repository.importBank(fileName, data, mapping)
                }
                Log.i(TAG, "导入: $fileName 题数=$count 映射=$mapping")
                if (count == 0) {
                    binding.btnImport.isEnabled = true
                    binding.tvResult.text = getString(R.string.import_zero)
                    Toast.makeText(this@ImportActivity, R.string.import_zero, Toast.LENGTH_LONG).show()
                    return@launch
                }
                binding.tvResult.text = getString(com.qweexam.app.R.string.import_success, count)
                Toast.makeText(this@ImportActivity, getString(com.qweexam.app.R.string.import_success, count), Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                binding.btnImport.isEnabled = true
                binding.tvResult.text = "导入失败：${e.message}"
                Log.e(TAG, "导入失败", e)
            }
        }
    }

    companion object {
        private const val TAG = "QWeExam-Import"
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx) ?: "题库"
                }
            }
        return "题库"
    }
}
