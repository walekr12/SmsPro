package com.tool.smspro.util

import java.text.SimpleDateFormat
import java.util.*

object PhoneUtils {
    private val PHONE_REGEX = Regex("^1[3-9]\\d{9}$")

    fun isValidPhone(phone: String): Boolean = PHONE_REGEX.matches(phone.trim())

    fun normalizePhone(phone: String): String {
        var p = phone.trim().replace(Regex("[\\s.\\-]"), "")
        // Handle scientific notation from Excel
        if (p.matches(Regex("^\\d+\\.?\\d*[eE]\\+?\\d+$"))) {
            p = p.toDouble().toLong().toString()
        }
        return p
    }
}

object TemplateUtils {
    fun replaceVars(content: String, name: String, company: String, amount: String = "0.00", date: String? = null): String {
        val d = date ?: SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date())
        return content
            .replace("{姓名}", name)
            .replace("{公司}", company)
            .replace("{金额}", amount)
            .replace("{日期}", d)
    }
}
