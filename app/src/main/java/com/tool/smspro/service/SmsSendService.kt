package com.tool.smspro.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import com.tool.smspro.App
import com.tool.smspro.MainActivity
import com.tool.smspro.R
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

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
        const val ACTION_SMS_SENT = "com.tool.smspro.SMS_SENT"
        const val EXTRA_SMS_TOKEN = "sms_token"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_PART_COUNT = "part_count"

        var isRunning = false
        var isPaused = false
        var currentProgress = 0
        var totalCount = 0
        var successCount = 0
        var failCount = 0
        var onProgressUpdate: ((Int, Int, Int, Int, String) -> Unit)? = null
        var onComplete: ((Int, Int) -> Unit)? = null

        private val sentCallbacks = ConcurrentHashMap<String, SmsSentAccumulator>()

        fun handleSentResult(intent: Intent?, resultCode: Int) {
            val token = intent?.getStringExtra(EXTRA_SMS_TOKEN) ?: return
            val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0)
            val errorCode = intent.getIntExtra("errorCode", Int.MIN_VALUE)
            sentCallbacks[token]?.addResult(partIndex, resultCode, errorCode)
        }
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

            val result = sendSingleSms(record.phone, record.content, simCard)
            val status = if (result.success) "success" else "fail"
            db.sendRecordDao().updateStatus(record.id, status, System.currentTimeMillis())

            if (result.success) successCount++ else failCount++
            currentProgress = index + 1

            val logMsg = "[${currentProgress}/${totalCount}] ${record.phone} - ${if (result.success) "发送成功" else "发送失败"}；${result.detail}"
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

    private suspend fun sendSingleSms(phone: String, message: String, simCard: Int): SmsSendResult {
        var token: String? = null
        return try {
            val managerInfo = getSmsManager(simCard)
            val smsManager = managerInfo.manager

            val parts = smsManager.divideMessage(message)
            token = "${System.currentTimeMillis()}-${System.nanoTime()}-$phone"
            val accumulator = SmsSentAccumulator(parts.size)
            sentCallbacks[token] = accumulator
            val sentIntents = ArrayList<PendingIntent>(parts.size).apply {
                parts.indices.forEach { index ->
                    add(createSentPendingIntent(token, index, parts.size))
                }
            }

            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
            } else {
                smsManager.sendTextMessage(phone, null, message, sentIntents.first(), null)
            }

            val sentResult = withTimeoutOrNull(120_000L) { accumulator.await() }
                ?: SmsSendResult(false, "等待系统发送回执超时；parts=${parts.size}；${managerInfo.detail}")

            sentResult.copy(detail = "${sentResult.detail}；parts=${parts.size}；${managerInfo.detail}")
        } catch (e: Exception) {
            e.printStackTrace()
            SmsSendResult(false, "调用 SmsManager 异常：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}")
        } finally {
            token?.let { sentCallbacks.remove(it) }
        }
    }

    private fun createSentPendingIntent(token: String, partIndex: Int, partCount: Int): PendingIntent {
        val intent = Intent(this, SmsSentReceiver::class.java).apply {
            action = ACTION_SMS_SENT
            putExtra(EXTRA_SMS_TOKEN, token)
            putExtra(EXTRA_PART_INDEX, partIndex)
            putExtra(EXTRA_PART_COUNT, partCount)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(this, "$token-$partIndex".hashCode(), intent, flags)
    }

    private fun getSmsManager(simCard: Int): SmsManagerInfo {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return SmsManagerInfo(SmsManager.getDefault(), "SIM=默认；Android 版本不支持订阅 ID")
        }

        val canReadPhoneState = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val activeSubscriptions = if (canReadPhoneState) getActiveSubscriptions() else emptyList()
        val activeDetail = formatActiveSubscriptions(activeSubscriptions)

        if (simCard <= 0) {
            val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return SmsManagerInfo(
                    SmsManager.getSmsManagerForSubscriptionId(defaultSubId),
                    "SIM=默认；defaultSmsSubscriptionId=$defaultSubId$activeDetail"
                )
            }

            if (activeSubscriptions.size == 1) {
                val sub = activeSubscriptions.first()
                return SmsManagerInfo(
                    SmsManager.getSmsManagerForSubscriptionId(sub.subscriptionId),
                    "SIM=默认；系统未设置默认短信卡，已使用唯一活跃 SIM(slot=${sub.simSlotIndex + 1}, subscriptionId=${sub.subscriptionId})$activeDetail"
                )
            }

            return SmsManagerInfo(
                SmsManager.getDefault(),
                "SIM=默认；系统未设置默认短信卡，已回退 SmsManager.getDefault()$activeDetail"
            )
        }

        if (!canReadPhoneState) {
            return SmsManagerInfo(SmsManager.getDefault(), "SIM=$simCard；未授予 READ_PHONE_STATE，已回退默认 SIM")
        }

        val slotSubscription = activeSubscriptions.firstOrNull { it.simSlotIndex == simCard - 1 }
        if (slotSubscription != null) {
            return SmsManagerInfo(
                SmsManager.getSmsManagerForSubscriptionId(slotSubscription.subscriptionId),
                "SIM=$simCard；subscriptionId=${slotSubscription.subscriptionId}$activeDetail"
            )
        }

        val orderedSubscription = activeSubscriptions.getOrNull(simCard - 1)
        return if (orderedSubscription != null) {
            SmsManagerInfo(
                SmsManager.getSmsManagerForSubscriptionId(orderedSubscription.subscriptionId),
                "SIM=$simCard；未匹配到卡槽，已按活跃列表第 $simCard 张 SIM 使用 subscriptionId=${orderedSubscription.subscriptionId}$activeDetail"
            )
        } else {
            SmsManagerInfo(
                SmsManager.getDefault(),
                "SIM=$simCard；未找到可用 subscriptionId，已回退默认 SIM$activeDetail"
            )
        }
    }

    private fun getActiveSubscriptions(): List<android.telephony.SubscriptionInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
            ) return emptyList()
            val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            @Suppress("MissingPermission")
            sm.activeSubscriptionInfoList ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun formatActiveSubscriptions(activeSubscriptions: List<android.telephony.SubscriptionInfo>): String {
        if (activeSubscriptions.isEmpty()) return ""
        val detail = activeSubscriptions.joinToString(",") {
            "slot=${it.simSlotIndex + 1}/subId=${it.subscriptionId}"
        }
        return "；activeSIM=[$detail]"
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

data class SmsSendResult(
    val success: Boolean,
    val detail: String
)

private data class SmsManagerInfo(
    val manager: SmsManager,
    val detail: String
)

private class SmsSentAccumulator(private val partCount: Int) {
    private val deferred = CompletableDeferred<SmsSendResult>()
    private val results = mutableMapOf<Int, PartResult>()

    @Synchronized
    fun addResult(partIndex: Int, resultCode: Int, errorCode: Int) {
        if (deferred.isCompleted) return
        results[partIndex] = PartResult(resultCode, errorCode)
        if (results.size < partCount) return

        val failed = results.toSortedMap().filterValues { it.resultCode != Activity.RESULT_OK }
        if (failed.isEmpty()) {
            deferred.complete(SmsSendResult(true, "系统回执 OK"))
        } else {
            val detail = failed.entries.joinToString("；") { (index, result) ->
                "part ${index + 1}: ${describeResultCode(result.resultCode)}${describeErrorCode(result.errorCode)}"
            }
            deferred.complete(SmsSendResult(false, detail))
        }
    }

    suspend fun await(): SmsSendResult = deferred.await()

    private data class PartResult(val resultCode: Int, val errorCode: Int)

    private fun describeErrorCode(errorCode: Int): String {
        return if (errorCode == Int.MIN_VALUE) "" else "，运营商错误码=$errorCode"
    }
}

private fun describeResultCode(resultCode: Int): String {
    return when (resultCode) {
        Activity.RESULT_OK -> "RESULT_OK"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE(通用失败)"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE(无服务)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "RESULT_ERROR_NULL_PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF(飞行模式/无线电关闭)"
        SmsManager.RESULT_MODEM_ERROR -> "RESULT_MODEM_ERROR(调制解调器发送失败)"
        SmsManager.RESULT_NETWORK_ERROR -> "RESULT_NETWORK_ERROR(网络发送失败)"
        SmsManager.RESULT_NETWORK_REJECT -> "RESULT_NETWORK_REJECT(网络拒绝)"
        SmsManager.RESULT_INVALID_STATE -> "RESULT_INVALID_STATE(短信模块状态异常)"
        SmsManager.RESULT_INVALID_ARGUMENTS -> "RESULT_INVALID_ARGUMENTS(短信参数异常)"
        SmsManager.RESULT_OPERATION_NOT_ALLOWED -> "RESULT_OPERATION_NOT_ALLOWED(系统不允许发送)"
        else -> "resultCode=$resultCode"
    }
}
