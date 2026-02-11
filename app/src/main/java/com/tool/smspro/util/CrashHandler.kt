package com.tool.smspro.util

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.ScrollView
import android.widget.TextView
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {

    private var context: Context? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    companion object {
        val instance: CrashHandler by lazy { CrashHandler() }
    }

    fun init(ctx: Context) {
        context = ctx.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        val info = buildCrashInfo(e)
        // Save to shared preferences for display on next launch
        try {
            val prefs = context?.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
            prefs?.edit()?.putString("last_crash", info)?.putLong("crash_time", System.currentTimeMillis())?.apply()
        } catch (_: Exception) {}

        // Try to show dialog on main thread
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    showCrashDialog(info)
                } catch (_: Exception) {
                    killProcess()
                }
            }
            // Keep thread alive for dialog
            Thread.sleep(60000)
        } catch (_: Exception) {
            killProcess()
        }
    }

    private fun buildCrashInfo(e: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        e.printStackTrace(pw)
        val stackTrace = sw.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

        return buildString {
            appendLine("===== 短信管家 Pro 崩溃报告 =====")
            appendLine("时间: $time")
            appendLine("设备: ${Build.BRAND} ${Build.MODEL}")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("应用版本: 1.0.0")
            appendLine()
            appendLine("--- 异常信息 ---")
            appendLine("线程: ${Thread.currentThread().name}")
            appendLine("类型: ${e.javaClass.name}")
            appendLine("消息: ${e.message}")
            appendLine()
            appendLine("--- 堆栈跟踪 ---")
            appendLine(stackTrace)
        }
    }

    private fun showCrashDialog(info: String) {
        val ctx = context ?: return
        // Need an activity context for dialog; use a new activity-like window
        val tv = TextView(ctx).apply {
            text = info
            textSize = 11f
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
        }
        val sv = ScrollView(ctx).apply { addView(tv) }

        // We can't show a dialog without an Activity, so we kill after saving log
        // The crash log will be shown on next app launch
        killProcess()
    }

    private fun killProcess() {
        Process.killProcess(Process.myPid())
        exitProcess(1)
    }

    /**
     * Call this in MainActivity.onCreate() to check and display last crash
     */
    fun checkAndShowLastCrash(activity: Activity) {
        val prefs = activity.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
        val lastCrash = prefs.getString("last_crash", null) ?: return
        val crashTime = prefs.getLong("crash_time", 0)
        // Only show if crash was within last 5 minutes
        if (System.currentTimeMillis() - crashTime > 5 * 60 * 1000) {
            prefs.edit().remove("last_crash").remove("crash_time").apply()
            return
        }

        prefs.edit().remove("last_crash").remove("crash_time").apply()

        val tv = TextView(activity).apply {
            text = lastCrash
            textSize = 11f
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val sv = ScrollView(activity).apply { addView(tv) }

        AlertDialog.Builder(activity)
            .setTitle("应用上次发生了崩溃")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制日志") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash_log", lastCrash))
            }
            .show()
    }
}
