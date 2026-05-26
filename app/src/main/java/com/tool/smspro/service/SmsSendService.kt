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
        // 第一次尝试：使用指定的 SIM 卡
        val firstResult = doSendSms(phone, message, simCard)

        // 如果第一次成功，直接返回
        if (firstResult.success) return firstResult

        // 如果失败且不是使用默认 SmsManager，尝试用 SmsManager.getDefault() 重试
        val isModemOrNetworkError = firstResult.detail.let {
            it.contains("MODEM_ERROR") || it.contains("NETWORK_ERROR") ||
            it.contains("NETWORK_REJECT") || it.contains("GENERIC_FAILURE") ||
            it.contains("INVALID_STATE")
        }

        if (isModemOrNetworkError) {
            // 延迟 2 秒后用 SmsManager.getDefault() 重试
            delay(2000)
            val retryResult = doSendSmsWithDefaultManager(phone, message)
            if (retryResult.success) {
                return retryResult.copy(detail = "${retryResult.detail}（首次失败后用 getDefault() 重试成功）")
            }
            // 两次都失败，返回两次的信息
            return SmsSendResult(false, "首次: ${firstResult.detail}；重试(getDefault): ${retryResult.detail}")
        }

        return firstResult
    }

    private suspend fun doSendSms(phone: String, message: String, simCard: Int): SmsSendResult {
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

    private suspend fun doSendSmsWithDefaultManager(phone: String, message: String): SmsSendResult {
        var token: String? = null
        return try {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()

            val parts = smsManager.divideMessage(message)
            token = "retry-${System.currentTimeMillis()}-${System.nanoTime()}-$phone"
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
                ?: SmsSendResult(false, "等待系统发送回执超时(getDefault)；parts=${parts.size}")

            sentResult.copy(detail = "${sentResult.detail}；parts=${parts.size}；SIM=getDefault()")
        } catch (e: Exception) {
            e.printStackTrace()
            SmsSendResult(false, "getDefault() 重试异常：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}")
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
            return SmsManagerInfo(SmsManager.getDefault(), "SIM=默认；Android版本不支持订阅ID")
        }

        val canReadPhoneState = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val activeSubscriptions = if (canReadPhoneState) getActiveSubscriptions() else emptyList()
        val activeDetail = formatActiveSubscriptions(activeSubscriptions)
        val permDetail = "；READ_PHONE_STATE=${if (canReadPhoneState) "已授权" else "未授权"}"

        // ===== 选择"默认" =====
        if (simCard <= 0) {
            val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return SmsManagerInfo(
                    SmsManager.getSmsManagerForSubscriptionId(defaultSubId),
                    "SIM=默认；defaultSmsSubscriptionId=$defaultSubId$activeDetail$permDetail"
                )
            }

            if (activeSubscriptions.size == 1) {
                val sub = activeSubscriptions.first()
                return SmsManagerInfo(
                    SmsManager.getSmsManagerForSubscriptionId(sub.subscriptionId),
                    "SIM=默认；系统未设置默认短信卡，已使用唯一活跃SIM(slot=${sub.simSlotIndex + 1}, subId=${sub.subscriptionId})$activeDetail$permDetail"
                )
            }

            // 如果有多张卡但没设默认，尝试用第二张（很多用户主卡在SIM2）
            if (activeSubscriptions.size > 1) {
                val sub = activeSubscriptions.last()
                return SmsManagerInfo(
                    SmsManager.getSmsManagerForSubscriptionId(sub.subscriptionId),
                    "SIM=默认；系统未设置默认短信卡，尝试最后一张活跃SIM(slot=${sub.simSlotIndex + 1}, subId=${sub.subscriptionId})$activeDetail$permDetail"
                )
            }

            return SmsManagerInfo(
                SmsManager.getDefault(),
                "SIM=默认；系统未设置默认短信卡，已回退SmsManager.getDefault()$activeDetail$permDetail"
            )
        }

        // ===== 选择了具体SIM卡 =====
        if (!canReadPhoneState) {
            // 没有权限，但还是尝试发——直接用 getDefault()
            return SmsManagerInfo(
                SmsManager.getDefault(),
                "SIM=$simCard；未授予READ_PHONE_STATE，无法查询订阅信息，已回退默认SIM$permDetail"
            )
        }

        // 策略1：按卡槽号匹配 (simSlotIndex == simCard - 1)
        val slotSubscription = activeSubscriptions.firstOrNull { it.simSlotIndex == simCard - 1 }
        if (slotSubscription != null) {
            return SmsManagerInfo(
                SmsManager.getSmsManagerForSubscriptionId(slotSubscription.subscriptionId),
                "SIM=$simCard；按卡槽匹配成功，subscriptionId=${slotSubscription.subscriptionId}$activeDetail$permDetail"
            )
        }

        // 策略2：按活跃列表顺序取第N张
        val orderedSubscription = activeSubscriptions.getOrNull(simCard - 1)
        if (orderedSubscription != null) {
            return SmsManagerInfo(
                SmsManager.getSmsManagerForSubscriptionId(orderedSubscription.subscriptionId),
                "SIM=$simCard；卡槽未匹配，按活跃列表第${simCard}张取subscriptionId=${orderedSubscription.subscriptionId}(实际slot=${orderedSubscription.simSlotIndex + 1})$activeDetail$permDetail"
            )
        }

        // 策略3：如果只检测到1张卡，不管用户选了SIM几，都用这张
        if (activeSubscriptions.size == 1) {
            val sub = activeSubscriptions.first()
            return SmsManagerInfo(
                SmsManager.getSmsManagerForSubscriptionId(sub.subscriptionId),
                "SIM=$simCard；仅检测到1张活跃SIM，强制使用subscriptionId=${sub.subscriptionId}(slot=${sub.simSlotIndex + 1})$activeDetail$permDetail"
            )
        }

        // 策略4：全部失败，回退默认
        return SmsManagerInfo(
            SmsManager.getDefault(),
            "SIM=$simCard；未找到可用subscriptionId(活跃卡数=${activeSubscriptions.size})，已回退默认SIM$activeDetail$permDetail"
        )
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
        if (activeSubscriptions.isEmpty()) return "；activeSIM=[无,可能未授权READ_PHONE_STATE]"
        val detail = activeSubscriptions.joinToString(",") { sub ->
            val carrier = sub.carrierName ?: "未知运营商"
            "slot=${sub.simSlotIndex + 1}/subId=${sub.subscriptionId}/$carrier"
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
