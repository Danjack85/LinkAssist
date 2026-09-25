package com.linkassist.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONObject

/** 收到短信 -> 提取验证码 -> 推给电脑 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Prefs.smsForwardingEnabled(context)) return
        try {
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (msgs.isEmpty()) return
            val sb = StringBuilder()
            var from = "未知号码"
            for (m in msgs) {
                if (!m.originatingAddress.isNullOrBlank()) from = m.originatingAddress!!
                sb.append(m.messageBody ?: "")
            }
            val body = sb.toString()
            val ts = System.currentTimeMillis()
            val code = CodeExtractor.extract(body)
            val json = JSONObject()
                .put("type", "sms")
                .put("from", from)
                .put("body", body)
                .put("ts", ts)
            if (code != null) json.put("code", code)
            LinkService.sendEvent(context, json)
            // 本地界面也留一条
            LinkService.addLocal(Msg(Msg.newId(), "sms", "out", from, "", body, code, ts))
        } catch (_: Exception) {
        }
    }
}
