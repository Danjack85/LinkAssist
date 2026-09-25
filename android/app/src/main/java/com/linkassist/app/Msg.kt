package com.linkassist.app

import java.util.concurrent.atomic.AtomicLong

/**
 * 一条同步到界面的消息
 * type: sms(短信验证码) / notif(APP通知) / chat(聊天)
 * direction: in(来自对方) / out(我发出)
 * pc: 消息来自/发往的电脑名(多电脑时用于区分)
 * key: 历史去重键(电脑消息为 "电脑id:消息id",本机消息为 "local:id")
 */
data class Msg(
    val id: Long,
    val type: String,
    val direction: String,
    val from: String,
    val title: String,
    val body: String,
    val code: String?,
    val ts: Long,
    val pc: String = "",
    val key: String? = null,
    /** 图片/视频消息的文件名(本地 Download/LinkAssist/ 下已存在,可内联显示) */
    val fileName: String? = null,
) {
    companion object {
        private val counter = AtomicLong(System.currentTimeMillis())
        fun newId(): Long = counter.incrementAndGet()
    }
}
