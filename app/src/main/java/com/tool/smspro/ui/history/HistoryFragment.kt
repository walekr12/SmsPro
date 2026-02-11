package com.tool.smspro.ui.history

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tool.smspro.App
import com.tool.smspro.R
import com.tool.smspro.data.entity.SendRecord
import com.tool.smspro.data.entity.SendTask
import com.tool.smspro.databinding.FragmentHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class HistoryFragment : Fragment() {
    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { (requireActivity().application as App).database }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        db.sendTaskDao().getAll().observe(viewLifecycleOwner) { tasks ->
            binding.emptyView.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
            binding.recyclerView.adapter = HistoryAdapter(tasks, dateFormat) { task -> showTaskDetail(task) }
        }
    }

    private fun showTaskDetail(task: SendTask) {
        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) { db.sendRecordDao().getByTaskList(task.id) }
            val dialogView = layoutInflater.inflate(R.layout.dialog_task_detail, null)
            val recycler = dialogView.findViewById<RecyclerView>(R.id.detailRecycler)
            val chipAll = dialogView.findViewById<TextView>(R.id.chipAll)
            val chipSuccess = dialogView.findViewById<TextView>(R.id.chipSuccess)
            val chipFail = dialogView.findViewById<TextView>(R.id.chipFail)

            recycler.layoutManager = LinearLayoutManager(requireContext())
            recycler.adapter = RecordAdapter(records)

            chipAll.setOnClickListener { recycler.adapter = RecordAdapter(records) }
            chipSuccess.setOnClickListener { recycler.adapter = RecordAdapter(records.filter { it.status == "success" }) }
            chipFail.setOnClickListener { recycler.adapter = RecordAdapter(records.filter { it.status == "fail" }) }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("任务详情")
                .setView(dialogView)
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class HistoryAdapter(
    private val items: List<SendTask>,
    private val dateFormat: SimpleDateFormat,
    private val onClick: (SendTask) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {
    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvTaskDate)
        val tvContent: TextView = view.findViewById(R.id.tvTaskContent)
        val tvStats: TextView = view.findViewById(R.id.tvTaskStats)
        val tvStatus: TextView = view.findViewById(R.id.tvTaskStatus)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.tvDate.text = dateFormat.format(Date(t.createdAt))
        holder.tvContent.text = t.content.take(50) + if (t.content.length > 50) "..." else ""
        holder.tvStats.text = "共${t.totalCount}条 · 成功${t.successCount} · 失败${t.failCount}"
        holder.tvStatus.text = if (t.status == "done") "已完成" else "进行中"
        holder.itemView.setOnClickListener { onClick(t) }
    }
    override fun getItemCount() = items.size
}

class RecordAdapter(
    private val items: List<SendRecord>
) : RecyclerView.Adapter<RecordAdapter.VH>() {
    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tvStatusIcon)
        val tvName: TextView = view.findViewById(R.id.tvRecordName)
        val tvPhone: TextView = view.findViewById(R.id.tvRecordPhone)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.tvIcon.text = if (r.status == "success") "✅" else "❌"
        holder.tvName.text = r.customerName.ifBlank { r.phone }
        holder.tvPhone.text = r.phone
    }
    override fun getItemCount() = items.size
}
