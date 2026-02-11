package com.tool.smspro.ui.customer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.tool.smspro.App
import com.tool.smspro.R
import com.tool.smspro.data.entity.Customer
import com.tool.smspro.data.entity.CustomerGroup
import com.tool.smspro.databinding.FragmentCustomerBinding
import com.tool.smspro.util.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.BufferedReader
import java.io.InputStreamReader

class CustomerFragment : Fragment() {
    private var _binding: FragmentCustomerBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { (requireActivity().application as App).database }
    private var groups: List<CustomerGroup> = emptyList()
    private var allCustomers: List<Customer> = emptyList()
    private var filterGroupId: Long? = null
    private var searchQuery: String = ""

    private val txtLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importFromTxt(it) }
    }
    private val excelLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importFromExcel(it) }
    }
    private val contactLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { importFromContact(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCustomerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                filterAndShow()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.groupFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                filterGroupId = if (position == 0) null else groups.getOrNull(position - 1)?.id
                filterAndShow()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.fab.setOnClickListener { showFabMenu() }

        db.customerDao().getAll().observe(viewLifecycleOwner) { customers ->
            allCustomers = customers
            filterAndShow()
        }
        db.customerGroupDao().getAll().observe(viewLifecycleOwner) { g ->
            groups = g
            updateGroupSpinner()
        }
    }

    private fun updateGroupSpinner() {
        val items = mutableListOf("全部分组")
        items.addAll(groups.map { it.name })
        binding.groupFilter.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun filterAndShow() {
        var list = allCustomers
        if (filterGroupId != null) list = list.filter { it.groupId == filterGroupId }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            list = list.filter { it.name.lowercase().contains(q) || it.phone.contains(q) }
        }
        binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        binding.recyclerView.adapter = CustomerAdapter(list, groups, ::showEditDialog, ::deleteCustomer)
    }

    private fun showFabMenu() {
        val items = arrayOf("手动添加", "从 TXT 导入", "从 Excel 导入", "从通讯录导入")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加客户")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showEditDialog(null)
                    1 -> txtLauncher.launch("text/plain")
                    2 -> excelLauncher.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    3 -> {
                        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                        contactLauncher.launch(intent)
                    }
                }
            }.show()
    }

    private fun showEditDialog(customer: Customer?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_customer, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.inputName)
        val phoneInput = dialogView.findViewById<TextInputEditText>(R.id.inputPhone)
        val companyInput = dialogView.findViewById<TextInputEditText>(R.id.inputCompany)
        val remarkInput = dialogView.findViewById<TextInputEditText>(R.id.inputRemark)
        val groupSpinner = dialogView.findViewById<Spinner>(R.id.spinnerGroup)

        val groupItems = mutableListOf("未分组")
        groupItems.addAll(groups.map { it.name })
        groupSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, groupItems)

        customer?.let {
            nameInput.setText(it.name)
            phoneInput.setText(it.phone)
            companyInput.setText(it.company)
            remarkInput.setText(it.remark)
            val idx = groups.indexOfFirst { g -> g.id == it.groupId }
            if (idx >= 0) groupSpinner.setSelection(idx + 1)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (customer == null) "添加客户" else "编辑客户")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val phone = phoneInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                val company = companyInput.text.toString().trim()
                val remark = remarkInput.text.toString().trim()
                val groupId = if (groupSpinner.selectedItemPosition == 0) null else groups[groupSpinner.selectedItemPosition - 1].id

                if (!PhoneUtils.isValidPhone(phone)) {
                    Snackbar.make(binding.root, "手机号格式不正确", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    val existId = customer?.id ?: 0L
                    val dup = withContext(Dispatchers.IO) { db.customerDao().countByPhoneExcluding(phone, existId) }
                    if (dup > 0) {
                        Snackbar.make(binding.root, "该手机号已存在", Snackbar.LENGTH_SHORT).show()
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        if (customer == null) {
                            db.customerDao().insert(Customer(name = name, phone = phone, company = company, remark = remark, groupId = groupId))
                        } else {
                            db.customerDao().update(customer.copy(name = name, phone = phone, company = company, remark = remark, groupId = groupId))
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteCustomer(customer: Customer) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除客户")
            .setMessage("确定删除 ${customer.displayName()} ？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { db.customerDao().delete(customer) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importFromTxt(uri: Uri) {
        lifecycleScope.launch {
            var added = 0; var skipped = 0
            withContext(Dispatchers.IO) {
                val reader = BufferedReader(InputStreamReader(requireContext().contentResolver.openInputStream(uri)))
                val customers = mutableListOf<Customer>()
                reader.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    var name = ""; var phone: String
                    if (line.contains(",") || line.contains("，")) {
                        val parts = line.split(Regex("[,，]")).map { it.trim() }
                        name = parts[0]; phone = parts.getOrElse(1) { "" }
                    } else {
                        phone = line.trim()
                    }
                    phone = PhoneUtils.normalizePhone(phone)
                    if (PhoneUtils.isValidPhone(phone)) {
                        customers.add(Customer(name = name, phone = phone, remark = "TXT导入"))
                        added++
                    } else { skipped++ }
                }
                reader.close()
                db.customerDao().insertAll(customers)
            }
            Snackbar.make(binding.root, "导入完成：${added}条成功，${skipped}条跳过", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun importFromExcel(uri: Uri) {
        lifecycleScope.launch {
            var added = 0; var skipped = 0
            withContext(Dispatchers.IO) {
                try {
                    val input = requireContext().contentResolver.openInputStream(uri)
                    val workbook = XSSFWorkbook(input)
                    val sheet = workbook.getSheetAt(0)
                    val customers = mutableListOf<Customer>()
                    for (row in sheet) {
                        var name = ""; var phone: String
                        val cell0 = row.getCell(0)
                        val cell1 = row.getCell(1)
                        if (cell1 != null) {
                            name = cell0?.toString()?.trim() ?: ""
                            phone = cell1.toString().trim()
                        } else {
                            phone = cell0?.toString()?.trim() ?: ""
                        }
                        phone = PhoneUtils.normalizePhone(phone)
                        if (PhoneUtils.isValidPhone(phone)) {
                            customers.add(Customer(name = name, phone = phone, remark = "Excel导入"))
                            added++
                        } else { skipped++ }
                    }
                    workbook.close()
                    db.customerDao().insertAll(customers)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Snackbar.make(binding.root, "Excel解析失败: ${e.message}", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            Snackbar.make(binding.root, "导入完成：${added}条成功，${skipped}条跳过", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun importFromContact(uri: Uri) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val cursor = requireContext().contentResolver.query(uri, arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ), null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val name = it.getString(0) ?: ""
                        var phone = it.getString(1) ?: ""
                        phone = PhoneUtils.normalizePhone(phone)
                        if (PhoneUtils.isValidPhone(phone)) {
                            db.customerDao().insert(Customer(name = name, phone = phone, remark = "通讯录导入"))
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class CustomerAdapter(
    private val items: List<Customer>,
    private val groups: List<CustomerGroup>,
    private val onEdit: (Customer) -> Unit,
    private val onDelete: (Customer) -> Unit
) : RecyclerView.Adapter<CustomerAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvPhone: TextView = view.findViewById(R.id.tvPhone)
        val tvInfo: TextView = view.findViewById(R.id.tvInfo)
        val tvGroup: TextView = view.findViewById(R.id.tvGroup)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_customer, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        if (c.name.isNotBlank()) {
            holder.tvName.text = c.name
            holder.tvPhone.text = c.phone
            holder.tvPhone.visibility = View.VISIBLE
        } else {
            holder.tvName.text = c.phone
            holder.tvPhone.visibility = View.GONE
        }
        val info = listOfNotNull(c.company.ifBlank { null }, c.remark.ifBlank { null }).joinToString(" · ")
        holder.tvInfo.text = info
        holder.tvInfo.visibility = if (info.isBlank()) View.GONE else View.VISIBLE

        val group = groups.firstOrNull { it.id == c.groupId }
        if (group != null) {
            holder.tvGroup.text = group.name
            holder.tvGroup.visibility = View.VISIBLE
            try { holder.tvGroup.setTextColor(android.graphics.Color.parseColor(group.color)) } catch (_: Exception) {}
        } else {
            holder.tvGroup.visibility = View.GONE
        }
        holder.btnEdit.setOnClickListener { onEdit(c) }
        holder.btnDelete.setOnClickListener { onDelete(c) }
    }

    override fun getItemCount() = items.size
}
