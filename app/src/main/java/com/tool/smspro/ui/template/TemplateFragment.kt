package com.tool.smspro.ui.template

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.tool.smspro.App
import com.tool.smspro.R
import com.tool.smspro.data.entity.SmsTemplate
import com.tool.smspro.databinding.FragmentTemplateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TemplateFragment : Fragment() {
    private var _binding: FragmentTemplateBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { (requireActivity().application as App).database }
    private val categories = listOf("全部", "营销", "通知", "节日", "催款", "其他")
    private var selectedCategory = "全部"
    private var allTemplates: List<SmsTemplate> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTemplateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.fab.setOnClickListener { showEditDialog(null) }
        setupCategoryChips()

        db.smsTemplateDao().getAll().observe(viewLifecycleOwner) { templates ->
            allTemplates = templates
            filterAndShow()
        }
    }

    private fun setupCategoryChips() {
        binding.chipGroup.removeAllViews()
        categories.forEach { cat ->
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = cat
                isCheckable = true
                isChecked = cat == selectedCategory
                setOnClickListener {
                    selectedCategory = cat
                    setupCategoryChips()
                    filterAndShow()
                }
            }
            binding.chipGroup.addView(chip)
        }
    }

    private fun filterAndShow() {
        val list = if (selectedCategory == "全部") allTemplates
        else allTemplates.filter { it.category == selectedCategory }
        binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        binding.recyclerView.adapter = TemplateAdapter(list, ::showEditDialog, ::deleteTemplate, ::togglePin, ::copyTemplate)
    }

    private fun showEditDialog(template: SmsTemplate?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_template, null)
        val titleInput = dialogView.findViewById<TextInputEditText>(R.id.inputTplTitle)
        val contentInput = dialogView.findViewById<TextInputEditText>(R.id.inputTplContent)
        val categorySpinner = dialogView.findViewById<Spinner>(R.id.spinnerCategory)
        val charCount = dialogView.findViewById<TextView>(R.id.tvCharCount)
        val btnName = dialogView.findViewById<TextView>(R.id.btnVarName)
        val btnCompany = dialogView.findViewById<TextView>(R.id.btnVarCompany)
        val btnAmount = dialogView.findViewById<TextView>(R.id.btnVarAmount)
        val btnDate = dialogView.findViewById<TextView>(R.id.btnVarDate)

        val catList = listOf("营销", "通知", "节日", "催款", "其他")
        categorySpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, catList)

        template?.let {
            titleInput.setText(it.title)
            contentInput.setText(it.content)
            val idx = catList.indexOf(it.category)
            if (idx >= 0) categorySpinner.setSelection(idx)
        }

        fun updateCount() {
            val len = contentInput.text?.length ?: 0
            charCount.text = if (len > 70) "$len 字 (超70字将拆分多条)" else "$len 字"
        }
        contentInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateCount() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        updateCount()

        fun insertVar(v: String) {
            val start = contentInput.selectionStart
            contentInput.text?.insert(start, v)
        }
        btnName.setOnClickListener { insertVar("{姓名}") }
        btnCompany.setOnClickListener { insertVar("{公司}") }
        btnAmount.setOnClickListener { insertVar("{金额}") }
        btnDate.setOnClickListener { insertVar("{日期}") }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (template == null) "新建模板" else "编辑模板")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val title = titleInput.text.toString().trim()
                val content = contentInput.text.toString().trim()
                val category = catList[categorySpinner.selectedItemPosition]
                if (title.isBlank() || content.isBlank()) {
                    Snackbar.make(binding.root, "请填写标题和内容", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    if (template == null) {
                        db.smsTemplateDao().insert(SmsTemplate(title = title, content = content, category = category))
                    } else {
                        db.smsTemplateDao().update(template.copy(title = title, content = content, category = category))
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteTemplate(t: SmsTemplate) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage("确定删除模板「${t.title}」？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { db.smsTemplateDao().delete(t) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun togglePin(t: SmsTemplate) {
        lifecycleScope.launch(Dispatchers.IO) { db.smsTemplateDao().setPinned(t.id, !t.pinned) }
    }

    private fun copyTemplate(t: SmsTemplate) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.smsTemplateDao().insert(t.copy(id = 0, title = t.title + " (副本)", pinned = false, useCount = 0, createdAt = System.currentTimeMillis()))
        }
        Snackbar.make(binding.root, "模板已复制", Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class TemplateAdapter(
    private val items: List<SmsTemplate>,
    private val onEdit: (SmsTemplate) -> Unit,
    private val onDelete: (SmsTemplate) -> Unit,
    private val onTogglePin: (SmsTemplate) -> Unit,
    private val onCopy: (SmsTemplate) -> Unit
) : RecyclerView.Adapter<TemplateAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvTplTitle)
        val tvContent: TextView = view.findViewById(R.id.tvTplContent)
        val tvCategory: TextView = view.findViewById(R.id.tvTplCategory)
        val tvMeta: TextView = view.findViewById(R.id.tvTplMeta)
        val btnPin: ImageButton = view.findViewById(R.id.btnPin)
        val btnCopy: ImageButton = view.findViewById(R.id.btnCopyTpl)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEditTpl)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteTpl)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_template, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.tvTitle.text = (if (t.pinned) "\uD83D\uDCCC " else "") + t.title
        holder.tvContent.text = t.content.take(80) + if (t.content.length > 80) "..." else ""
        holder.tvCategory.text = t.category
        holder.tvMeta.text = "使用 ${t.useCount} 次 · ${t.content.length} 字"
        holder.btnPin.setImageResource(if (t.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin)
        holder.btnPin.setOnClickListener { onTogglePin(t) }
        holder.btnCopy.setOnClickListener { onCopy(t) }
        holder.btnEdit.setOnClickListener { onEdit(t) }
        holder.btnDelete.setOnClickListener { onDelete(t) }
    }

    override fun getItemCount() = items.size
}
