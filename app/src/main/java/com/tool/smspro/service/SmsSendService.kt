package com.tool.smspro.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import com.tool.smspro.App
import com.tool.smspro.MainActivity
import com.tool.smspro.R
import com.tool.smspro.data.entity.SendRecord
import com.tool.smspro.util.TemplateUtils
import kotlinx.coroutines.*

class SmsSendService : Service() {

    companion object {
        const val CHANNEL_ID = "sms_send_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_SIM_CARD = "sim_card"
        const val ACTION_PAUSE = "com.tool.smspro.PAUSE"
        const val ACTION_RESUME = "com.tool.smspro.RESUME"
        const val ACTION_CANCEL = "com.tool.smspro.CANCEL"

        var isRunning = false
        var isPaused = false
        var currentProgress = 0
        var totalCount = 0
        var successCount = 0
        var failCount = 0
        var onProgressUpdate: ((Int, Int, Int, Int, String) -> Unit)? = null
        var onComplete: ((Int, Int) -> Unit)? = null
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { isPaused = true; return START_STICKY }
            ACTION_RESUME -> { isPaused = false; return START_STICKY }
            ACTION_CANCEL -> { cancelSending(); return START_NOT_STICKY }
        }

        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, -1) ?: -1
        val interval = intent?.getIntExtra(EXTRA_INTERVAL, 3) ?: 3
        val simCard = intent?.getIntExtra(EXTRA_SIM_CARD, 0) ?: 0

        if (taskId == -1L) { stopSelf(); return START_NOT_STICKY }

        startForeground(NOTIFICATION_ID, buildNotification("准备发送...", 0, 0))
        acquireWakeLock()

        isRunning = true
        isPaused = false
        currentProgress = 0
        successCount = 0
        failCount = 0

        scope.launch {
            sendMessages(taskId, interval, simCard)
        }

        return START_STICKY
    }

    private suspend fun sendMessages(taskId: Long, interval: Int, simCard: Int) {
        val db = (application as App).database
        val records = db.sendRecordDao().getByTaskList(taskId)
        totalCount = records.size

        for ((index, record) in records.withIndex()) {
            if (!isRunning) break
            while (isPaused && isRunning) { delay(500) }
            if (!isRunning) break

            val success = sendSingleSms(record.phone, record.content, simCard)
            val status = if (success) "success" else "fail"
            db.sendRecordDao().updateStatus(record.id, status, System.currentTimeMillis())

            if (success) successCount++ else failCount++
            currentProgress = index + 1

            val logMsg = "[${currentProgress}/${totalCount}] ${record.phone} - ${if (success) "发送成功" else "发送失败"}"
            withContext(Dispatchers.Main) {
                onProgressUpdate?.invoke(currentProgress, totalCount, successCount, failCount, logMsg)
            }

            updateNotification("发送中 $currentProgress/$totalCount", currentProgress, totalCount)

            if (index < records.size - 1 && isRunning) {
                delay(interval * 1000L)
            }
        }

        db.sendTaskDao().updateCounts(taskId, successCount, failCount, "done")

        withContext(Dispatchers.Main) {
            onComplete?.invoke(successCount, failCount)
        }

        isRunning = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendSingleSms(phone: String, message: String, simCard: Int): Boolean {
        return try {
            val smsManager = if (simCard > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subId = getSubscriptionId(simCard)
                if (subId != -1) SmsManager.getSmsManagerForSubscriptionId(subId)
                else SmsManager.getDefault()
            } else {
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phone, null, message, null, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun getSubscriptionId(simSlot: Int): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                @Suppress("MissingPermission")
                val list = sm.activeSubscriptionInfoList ?: return -1
                if (simSlot <= list.size) list[simSlot - 1].subscriptionId else -1
            } else -1
        } catch (e: Exception) { -1 }
    }

    private fun cancelSending() {
        isRunning = false
        isPaused = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "短信发送", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "短信群发进度通知" }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("短信管家 Pro")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_send)
            .setContentIntent(pi)
            .setOngoing(true)
            .apply {
                if (max > 0) setProgress(max, progress, false)
            }
            .build()
    }

    private fun updateNotification(text: String, progress: Int, max: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress, max))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsPro::SendWakeLock")
        wakeLock?.acquire(60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        job.cancel()
        releaseWakeLock()
    }
}
