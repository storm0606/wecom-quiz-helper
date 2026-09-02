package com.qweexam.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qweexam.app.R
import com.qweexam.app.data.db.AppDatabase
import com.qweexam.app.data.db.QuestionEntity
import com.qweexam.app.databinding.ActivityBankPreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 题库内容读取（v2.4）：选择某题库后，以表格形式抽查导入结果 ——
 * 表头与字段逐列对应（题型/题目/选项A–F/答案），每种题型最多展示前 5 题，
 * 用于核对列映射是否正确。
 */
class BankPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBankPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBankPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bankId = intent.getLongExtra(EXTRA_BANK_ID, -1)
        if (bankId <= 0) {
            finish()
            return
        }
        val bankName = intent.getStringExtra(EXTRA_BANK_NAME).orEmpty()
        supportActionBar?.setTitle(bankName.ifEmpty { getString(R.string.bank_preview_title) })
        load(bankId)
    }

    private fun load(bankId: Long) {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                AppDatabase.get(this@BankPreviewActivity)
                    .questionDao().getByBank(bankId).sortedBy { it.id }
            }
            if (rows.isEmpty()) {
                binding.tvSummary.text = getString(R.string.bank_preview_empty)
                return@launch
            }
            buildTable(rows)
        }
    }

    private fun buildTable(rows: List<QuestionEntity>) {
        val cols = BankPreviewModel.tableColumns(rows)
        val counts = BankPreviewModel.typeCounts(rows)
            .joinToString(" · ") { getString(R.string.bank_preview_type_count, it.first, it.second) }
        binding.tvSummary.text = getString(R.string.bank_preview_summary, rows.size) +
            (if (counts.isEmpty()) "" else "\n$counts")

        val table: TableLayout = binding.tblPreview
        // 表头行（灰底加粗；isAnswer=false 表头不染色）
        table.addView(buildRow(cols.map { it.header to it.header }, header = true, alt = false))
        // 数据行：按题型抽样（每类最多前 5 题），交替底色、答案列浅黄高亮便于核对
        var alt = false
        for ((_, group) in BankPreviewModel.sampleByType(rows)) {
            for (q in group) {
                table.addView(
                    buildRow(
                        cols.map { col -> col.value(q) to col.header },
                        header = false,
                        alt = alt
                    )
                )
                alt = !alt
            }
        }
    }

    /** 组装一行：每项为 (显示文本, 列头名)（列头名用于取列宽与答案列高亮） */
    private fun buildRow(items: List<Pair<String, String>>, header: Boolean, alt: Boolean): TableRow {
        val row = TableRow(this)
        items.forEachIndexed { i, (text, name) ->
            row.addView(cell(text, widthDp(name), header, alt, isAnswer = name == "答案"))
        }
        return row
    }

    private fun widthDp(name: String): Int = when {
        name == "题型" -> 60
        name == "题目" -> 220
        name == "答案" -> 84
        name.startsWith("选项") -> 140
        else -> 96
    }

    private fun cell(
        text: String,
        widthDp: Int,
        header: Boolean,
        alt: Boolean,
        isAnswer: Boolean
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = if (header) 13f else 12f
        setTypeface(null, if (header) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(getColor(if (header) R.color.table_header_text else R.color.table_text))
        val bg = when {
            header -> R.color.table_header_bg
            isAnswer -> R.color.table_answer_bg
            alt -> R.color.table_row_alt
            else -> android.R.color.white
        }
        setBackgroundColor(getColor(bg))
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        minHeight = dp(36)
        layoutParams = TableRow.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_BANK_ID = "bank_id"
        const val EXTRA_BANK_NAME = "bank_name"
    }
}
