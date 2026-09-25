package com.linkassist.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * 通知监听:把邮件等 APP 通知转发到电脑(用于接收邮箱验证码)
 * 需要用户在 系统设置 -> 通知使用权 中授权
 */
class CodeNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.notificationForwardingEnabled(this)) return
        try {
            if (sbn.packageName == packageName) return
            if (sbn.isOngoing) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val ex = n.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            val body = if (big.length > text.length) big else text
            if (body.isBlank() && title.isBlank()) return

            val ts = System.currentTimeMillis()
            val code = CodeExtractor.extract("$title $body")
            val json = JSONObject()
                .put("type", "notif")
                .put("app", sbn.packageName)
                .put("title", title)
                .put("body", body)
                .put("ts", ts)
            if (code != null) json.put("code", code)
            LinkService.sendEvent(this, json)
            LinkService.addLocal(Msg(Msg.newId(), "notif", "out", sbn.packageName, title, body, code, ts))
        } catch (_: Exception) {
        }
    }
}
