package com.tool.smspro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        SmsSendService.handleSentResult(intent, resultCode)
    }
}

class SmsDeliveredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {}
}
