package com.qweexam.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.recyclerview.widget.RecyclerView
import com.qweexam.app.data.parser.ColumnRole
import com.qweexam.app.data.parser.ColumnRoles
import com.qweexam.app.databinding.ItemColumnMappingBinding

/**
 * 列映射列表适配器：每列一行（列名 + 角色下拉框）。
 *
 * 选择值直接写回数据模型（items[i].selectedRole），
 * 不缓存视图引用 —— 避免 RecyclerView 滚动复用时读到错位的选择（v1.2 修复）。
 */
class ColumnMappingAdapter(
    val items: MutableList<ColumnRole>
) : RecyclerView.Adapter<ColumnMappingAdapter.VH>() {

    class VH(val binding: ItemColumnMappingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemColumnMappingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvColumnName.text = item.columnName
        val spinner = holder.binding.spRole
        val adapter = ArrayAdapter(
            holder.itemView.context,
            android.R.layout.simple_spinner_item,
            ColumnRoles.ALL
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        // 用户选择直接写回数据模型（此处 setSelection 也会触发监听，写回的就是本列的建议值）
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                if (position < items.size) {
                    items[position].selectedRole = ColumnRoles.ALL[pos]
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val index = ColumnRoles.ALL.indexOf(item.selectedRole).coerceAtLeast(0)
        spinner.setSelection(index)
    }

    /** 收集当前每列选择的角色（读数据模型，与视图复用无关） */
    fun collectRoles(): List<String> = items.map { it.selectedRole }

    /** 根据角色列表构建 {角色 -> 列下标} 映射；无效配置抛异常 */
    fun buildMapping(roles: List<String>): Map<String, Int> {
        val mapping = HashMap<String, Int>()
        for ((colIdx, role) in roles.withIndex()) {
            if (role == ColumnRoles.IGNORE) continue
            mapping[role] = colIdx
        }
        if (!mapping.containsKey(ColumnRoles.STEM)) {
            throw IllegalArgumentException("必须指定至少一列为「题目」")
        }
        if (!mapping.containsKey(ColumnRoles.ANSWER)) {
            throw IllegalArgumentException("必须指定至少一列为「答案」")
        }
        return mapping
    }
}
