package com.tool.smspro.ui.send

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tool.smspro.App
import com.tool.smspro.R
import com.tool.smspro.data.entity.Customer
import com.tool.smspro.data.entity.CustomerGroup
import com.tool.smspro.data.entity.SendRecord
import com.tool.smspro.data.entity.SendTask
import com.tool.smspro.data.entity.SmsTemplate
import com.tool.smspro.databinding.FragmentSendBinding
import com.tool.smspro.service.SmsSendService
import com.tool.smspro.util.TemplateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SendFragment : Fragment() {
    private var _binding: FragmentSendBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { (requireActivity().application as App).database }

    private var currentStep = 1
    private var groups: List<CustomerGroup> = emptyList()
    private var allCustomers: List<Customer> = emptyList()
    private var templates: List<SmsTemplate> = emptyList()
    private val selectedIds = mutableSetOf<Long>()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.all { it.value }) doStartSend()
        else Snackbar.make(binding.root, "需要短信权限才能发送", Snackbar.LENGTH_LONG).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSendBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.customerRecycler.layoutManager = LinearLayoutManager(requireContext())
        showStep(1)

        db.customerGroupDao().getAll().observe(viewLifecycleOwner) { g ->
            groups = g
            val items = mutableListOf("全部分组")
            items.addAll(g.map { it.name })
            binding.sendGroupFilter.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, items)
        }
        db.customerDao().getAll().observe(viewLifecycleOwner) { c ->
            allCustomers = c
            refreshCustomerList()
        }
        db.smsTemplateDao().getAll().observe(viewLifecycleOwner) { t ->
            templates = t
            val items = mutableListOf("-- 手动输入 --")
            items.addAll(t.map { it.title })
            binding.templateSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, items)
        }

        binding.sendGroupFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { refreshCustomerList() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnStep1Next.setOnClickListener {
            if (selectedIds.isEmpty()) { Snackbar.make(binding.root, "请至少选择一位客户", Snackbar.LENGTH_SHORT).show(); return@setOnClickListener }
            showStep(2)
        }

        binding.templateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos > 0) {
                    val t = templates[pos - 1]
                    binding.sendContentInput.setText(t.content)
                    lifecycleScope.launch(Dispatchers.IO) { db.smsTemplateDao().incrementUseCount(t.id) }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        binding.btnVarName.setOnClickListener { insertVar("{姓名}") }
        binding.btnVarCompany.setOnClickListener { insertVar("{公司}") }
        binding.btnVarAmount.setOnClickListener { insertVar("{金额}") }
        binding.btnVarDate.setOnClickListener { insertVar("{日期}") }

        binding.sendContentInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val len = s?.length ?: 0
                binding.sendCharCount.text = if (len > 70) "$len 字 (超70字将拆分多条)" else "$len 字"
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.btnStep2Back.setOnClickListener { showStep(1) }
        binding.btnStep2Next.setOnClickListener {
            if (binding.sendContentInput.text.isNullOrBlank()) {
                Snackbar.make(binding.root, "请输入短信内容", Snackbar.LENGTH_SHORT).show(); return@setOnClickListener
            }
            prepareStep3()
            showStep(3)
        }

        binding.intervalSlider.addOnChangeListener { _, value, _ ->
            binding.intervalLabel.text = "${value.toInt()} 秒"
        }
        binding.btnStep3Back.setOnClickListener { showStep(2) }
        binding.btnStartSend.setOnClickListener { requestPermAndSend() }
        binding.btnPause.setOnClickListener { togglePause() }
        binding.btnCancel.setOnClickListener { cancelSend() }
        binding.btnNewTask.setOnClickListener { resetWizard() }
    }

    private fun refreshCustomerList() {
        val filterPos = binding.sendGroupFilter.selectedItemPosition
        val list = if (filterPos == 0) allCustomers
        else {
            val gid = groups.getOrNull(filterPos - 1)?.id
            allCustomers.filter { it.groupId == gid }
        }
        binding.customerRecycler.adapter = SelectCustomerAdapter(list, selectedIds) { id, checked ->
            if (checked) selectedIds.add(id) else selectedIds.remove(id)
            binding.selectedCount.text = "已选: ${selectedIds.size} 人"
        }
        binding.selectedCount.text = "已选: ${selectedIds.size} 人"
    }

    private fun toggleSelectAll() {
        val filterPos = binding.sendGroupFilter.selectedItemPosition
        val list = if (filterPos == 0) allCustomers
        else {
            val gid = groups.getOrNull(filterPos - 1)?.id
            allCustomers.filter { it.groupId == gid }
        }
        val allSelected = list.all { it.id in selectedIds }
        if (allSelected) list.forEach { selectedIds.remove(it.id) }
        else list.forEach { selectedIds.add(it.id) }
        refreshCustomerList()
    }

    private fun insertVar(v: String) {
        val start = binding.sendContentInput.selectionStart
        binding.sendContentInput.text?.insert(start, v)
    }

    private fun prepareStep3() {
        val total = selectedIds.size
        val interval = binding.intervalSlider.value.toInt()
        binding.confirmTotal.text = total.toString()
        val secs = total * interval
        binding.confirmTime.text = if (secs >= 60) "${secs / 60}分${secs % 60}秒" else "${secs}秒"
    }

    private fun showStep(step: Int) {
        currentStep = step
        binding.step1Layout.visibility = if (step == 1) View.VISIBLE else View.GONE
        binding.step2Layout.visibility = if (step == 2) View.VISIBLE else View.GONE
        binding.step3Layout.visibility = if (step == 3) View.VISIBLE else View.GONE
        binding.sendingLayout.visibility = View.GONE
        binding.resultLayout.visibility = View.GONE
        binding.stepIndicator1.setTextColor(ContextCompat.getColor(requireContext(), if (step >= 1) R.color.primary else R.color.outline))
        binding.stepIndicator2.setTextColor(ContextCompat.getColor(requireContext(), if (step >= 2) R.color.primary else R.color.outline))
        binding.stepIndicator3.setTextColor(ContextCompat.getColor(requireContext(), if (step >= 3) R.color.primary else R.color.outline))
    }

    private fun requestPermAndSend() {
        val perms = mutableListOf(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val needed = perms.filter { ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) doStartSend()
        else permLauncher.launch(needed.toTypedArray())
    }

    private fun doStartSend() {
        val content = binding.sendContentInput.text.toString()
        val interval = binding.intervalSlider.value.toInt()
        val simCard = binding.simSpinner.selectedItemPosition

        lifecycleScope.launch {
            val customers = withContext(Dispatchers.IO) { db.customerDao().getByIds(selectedIds.toList()) }
            if (customers.isEmpty()) return@launch

            val taskId = withContext(Dispatchers.IO) {
                val task = SendTask(content = content, totalCount = customers.size, interval = interval, simCard = simCard)
                val id = db.sendTaskDao().insert(task)
                val records = customers.map { c ->
                    val replaced = TemplateUtils.replaceVars(content, c.displayName(), c.company)
                    SendRecord(taskId = id, customerId = c.id, customerName = c.displayName(), phone = c.phone, content = replaced)
                }
                db.sendRecordDao().insertAll(records)
                id
            }

            binding.step3Layout.visibility = View.GONE
            binding.sendingLayout.visibility = View.VISIBLE
            binding.sendLog.text = ""
            binding.progressBar.max = customers.size
            binding.progressBar.progress = 0
            binding.btnPause.text = "暂停"
            binding.sendControls.visibility = View.VISIBLE

            SmsSendService.onProgressUpdate = { current, total, succ, fail, log ->
                binding.progressBar.progress = current
                binding.progressText.text = "$current / $total"
                binding.sendLog.append(log + "\n")
                binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
            }
            SmsSendService.onComplete = { succ, fail ->
                binding.sendControls.visibility = View.GONE
                binding.resultLayout.visibility = View.VISIBLE
                binding.resultSuccess.text = succ.toString()
                binding.resultFail.text = fail.toString()
                binding.btnRetryFailed.visibility = if (fail > 0) View.VISIBLE else View.GONE
            }

            val intent = Intent(requireContext(), SmsSendService::class.java).apply {
                putExtra(SmsSendService.EXTRA_TASK_ID, taskId)
                putExtra(SmsSendService.EXTRA_INTERVAL, interval)
                putExtra(SmsSendService.EXTRA_SIM_CARD, simCard)
            }
            ContextCompat.startForegroundService(requireContext(), intent)
        }
    }

    private fun togglePause() {
        val action = if (SmsSendService.isPaused) SmsSendService.ACTION_RESUME else SmsSendService.ACTION_PAUSE
        val intent = Intent(requireContext(), SmsSendService::class.java).apply { this.action = action }
        requireContext().startService(intent)
        binding.btnPause.text = if (SmsSendService.isPaused) "暂停" else "继续"
    }

    private fun cancelSend() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage("确定取消发送？")
            .setPositiveButton("确定") { _, _ ->
                val intent = Intent(requireContext(), SmsSendService::class.java).apply { action = SmsSendService.ACTION_CANCEL }
                requireContext().startService(intent)
                binding.sendControls.visibility = View.GONE
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun resetWizard() {
        selectedIds.clear()
        binding.sendContentInput.setText("")
        showStep(1)
        refreshCustomerList()
    }

    override fun onDestroyView() {
        SmsSendService.onProgressUpdate = null
        SmsSendService.onComplete = null
        super.onDestroyView()
        _binding = null
    }
}

class SelectCustomerAdapter(
    private val items: List<Customer>,
    private val selectedIds: Set<Long>,
    private val onToggle: (Long, Boolean) -> Unit
) : RecyclerView.Adapter<SelectCustomerAdapter.VH>() {
    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cb: CheckBox = view.findViewById(R.id.cbSelect)
        val tvName: TextView = view.findViewById(R.id.tvSelectName)
        val tvPhone: TextView = view.findViewById(R.id.tvSelectPhone)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_select_customer, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.cb.isChecked = c.id in selectedIds
        holder.tvName.text = c.displayName()
        holder.tvPhone.text = if (c.name.isNotBlank()) c.phone else ""
        holder.tvPhone.visibility = if (c.name.isNotBlank()) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            holder.cb.isChecked = !holder.cb.isChecked
            onToggle(c.id, holder.cb.isChecked)
        }
        holder.cb.setOnCheckedChangeListener(null)
        holder.cb.setOnCheckedChangeListener { _, checked -> onToggle(c.id, checked) }
    }
    override fun getItemCount() = items.size
}
