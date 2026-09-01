package com.qweexam.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qweexam.app.R
import com.qweexam.app.data.db.AppDatabase
import com.qweexam.app.data.db.BankEntity
import com.qweexam.app.data.repository.AnswerRepository
import com.qweexam.app.data.repository.BankRepository
import com.qweexam.app.databinding.ActivityBankListBinding
import com.qweexam.app.databinding.ItemBankBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 题库管理（v1.1/v1.2）：历史题库列表 + 重命名/删除 + 匹配范围（所有/指定）+ 设为当前 */
class BankListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBankListBinding
    private lateinit var bankRepository: BankRepository
    private lateinit var answerRepository: AnswerRepository
    private lateinit var adapter: BankListAdapter

    private var matchMode = "all"
    private var currentBankId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBankListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setTitle(R.string.module_bank)

        val db = AppDatabase.get(this)
        bankRepository = BankRepository(db)
        answerRepository = AnswerRepository(db)

        adapter = BankListAdapter { bank, action ->
            when (action) {
                BankAction.USE -> setCurrent(bank.id)
                BankAction.RENAME -> showRenameDialog(bank)
                BankAction.DELETE -> showDeleteDialog(bank)
            }
        }
        binding.rvBanks.layoutManager = LinearLayoutManager(this)
        binding.rvBanks.adapter = adapter
        binding.btnImportNew.setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
        binding.tgMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.btnModeAll) "all" else "specific"
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { answerRepository.setMatchMode(mode) }
                matchMode = mode
                refresh()
            }
        }
        loadState()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun loadState() {
        lifecycleScope.launch {
            val (mode, bankId) = withContext(Dispatchers.IO) {
                answerRepository.matchMode() to answerRepository.currentBankId()
            }
            matchMode = mode
            currentBankId = bankId
            binding.tgMode.check(if (mode == "all") R.id.btnModeAll else R.id.btnModeSpecific)
            refresh()
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val banks = withContext(Dispatchers.IO) {
                bankRepository.db.bankDao().getAll().map { bank ->
                    bank to bankRepository.db.questionDao().countByBank(bank.id)
                }
            }
            adapter.submit(banks, currentBankId, matchMode == "specific")
            binding.tvEmpty.visibility = if (banks.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun setCurrent(bankId: Long) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { answerRepository.setCurrentBank(bankId) }
            currentBankId = bankId
            refresh()
            showToast(getString(R.string.bank_current_set, adapter.bankName(bankId)))
        }
    }

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun showRenameDialog(bank: BankEntity) {
        val input = android.widget.EditText(this).apply {
            setText(bank.name)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bank_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { bankRepository.renameBank(bank.id, name) }
                        refresh()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(bank: BankEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.bank_delete)
            .setMessage(getString(R.string.bank_delete_confirm, bank.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { bankRepository.deleteBank(bank.id) }
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    enum class BankAction { USE, RENAME, DELETE }

    class BankListAdapter(
        private val onAction: (BankEntity, BankAction) -> Unit
    ) : RecyclerView.Adapter<BankListAdapter.VH>() {

        private val items = mutableListOf<Pair<BankEntity, Int>>()
        private var currentBankId: Long? = null
        private var specificMode = false
        private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        class VH(val binding: ItemBankBinding) : RecyclerView.ViewHolder(binding.root)

        fun submit(banks: List<Pair<BankEntity, Int>>, current: Long?, specific: Boolean) {
            items.clear()
            items.addAll(banks)
            currentBankId = current
            specificMode = specific
            notifyDataSetChanged()
        }

        fun bankName(bankId: Long): String =
            items.firstOrNull { it.first.id == bankId }?.first?.name ?: ""

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemBankBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (bank, count) = items[position]
            holder.binding.tvBankName.text = bank.name
            holder.binding.tvBankInfo.text =
                holder.itemView.context.getString(R.string.bank_count, count) +
                    " · " + fmt.format(Date(bank.createdAt))
            val isCurrent = specificMode && bank.id == currentBankId
            holder.binding.tvCurrentTag.visibility = if (isCurrent) View.VISIBLE else View.GONE
            holder.binding.btnUse.setOnClickListener { onAction(bank, BankAction.USE) }
            holder.binding.btnRename.setOnClickListener { onAction(bank, BankAction.RENAME) }
            holder.binding.btnDelete.setOnClickListener { onAction(bank, BankAction.DELETE) }
        }
    }
}
