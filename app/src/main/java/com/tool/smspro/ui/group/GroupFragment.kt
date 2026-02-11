package com.tool.smspro.ui.group

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
import com.tool.smspro.data.entity.CustomerGroup
import com.tool.smspro.databinding.FragmentGroupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupFragment : Fragment() {
    private var _binding: FragmentGroupBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { (requireActivity().application as App).database }
    private val colors = listOf("#6750A4","#E91E63","#FF9800","#4CAF50","#2196F3","#9C27B0","#795548","#607D8B","#F44336","#00BCD4")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.fab.setOnClickListener { showEditDialog(null) }

        db.customerGroupDao().getAll().observe(viewLifecycleOwner) { groups ->
            binding.emptyView.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
            lifecycleScope.launch {
                val customers = withContext(Dispatchers.IO) { db.customerDao().getAllList() }
                binding.recyclerView.adapter = GroupAdapter(groups, customers.groupBy { it.groupId }.mapValues { it.value.size }, ::showEditDialog, ::deleteGroup)
            }
        }
    }

    private fun showEditDialog(group: CustomerGroup?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_group, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.inputGroupName)
        val colorGroup = dialogView.findViewById<LinearLayout>(R.id.colorPickerLayout)

        var selectedColor = group?.color ?: colors[0]
        nameInput.setText(group?.name ?: "")

        colorGroup.removeAllViews()
        colors.forEach { color ->
            val dot = View(requireContext()).apply {
                val size = (32 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor(color))
                    if (color == selectedColor) { setStroke((3 * resources.displayMetrics.density).toInt(), android.graphics.Color.BLACK) }
                }
                setOnClickListener {
                    selectedColor = color
                    showEditDialog_refreshColors(colorGroup, colors, selectedColor)
                }
            }
            colorGroup.addView(dot)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (group == null) "新建分组" else "编辑分组")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    Snackbar.make(binding.root, "请输入分组名称", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    if (group == null) {
                        db.customerGroupDao().insert(CustomerGroup(name = name, color = selectedColor))
                    } else {
                        db.customerGroupDao().update(group.copy(name = name, color = selectedColor))
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditDialog_refreshColors(container: LinearLayout, colorList: List<String>, selected: String) {
        for (i in 0 until container.childCount) {
            val dot = container.getChildAt(i)
            dot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor(colorList[i]))
                if (colorList[i] == selected) { setStroke((3 * dot.resources.displayMetrics.density).toInt(), android.graphics.Color.BLACK) }
            }
        }
    }

    private fun deleteGroup(group: CustomerGroup) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除分组")
            .setMessage("删除后组内客户将变为未分组，确定？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { db.customerGroupDao().deleteById(group.id) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class GroupAdapter(
    private val items: List<CustomerGroup>,
    private val counts: Map<Long?, Int>,
    private val onEdit: (CustomerGroup) -> Unit,
    private val onDelete: (CustomerGroup) -> Unit
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val colorDot: View = view.findViewById(R.id.colorDot)
        val tvName: TextView = view.findViewById(R.id.tvGroupName)
        val tvCount: TextView = view.findViewById(R.id.tvCount)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEditGroup)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteGroup)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = items[position]
        holder.tvName.text = g.name
        holder.tvCount.text = "${counts[g.id] ?: 0} 位客户"
        try {
            val gd = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor(g.color))
            }
            holder.colorDot.background = gd
        } catch (_: Exception) {}
        holder.btnEdit.setOnClickListener { onEdit(g) }
        holder.btnDelete.setOnClickListener { onDelete(g) }
    }

    override fun getItemCount() = items.size
}
