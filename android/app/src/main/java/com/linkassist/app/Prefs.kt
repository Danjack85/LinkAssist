package com.linkassist.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一台已配对的电脑 */
data class PcProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
)

/** 连接配置存取:支持同时保存/连接多台电脑 */
object Prefs {
    private const val FILE = "linkassist"
    private const val KEY_PCS = "pcs"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, 0)

    fun newId(): String = "pc-" + UUID.randomUUID().toString().substring(0, 8)

    /**
     * 兼容粘贴 "http://192.168.1.245:8765/xxx" 这类完整地址
     * 返回 (纯主机名, 地址里带的端口?)
     */
    fun cleanHost(raw: String): Pair<String, Int?> {
        var s = raw.trim()
        s = s.replace(Regex("^[A-Za-z][A-Za-z0-9+.-]*://"), "")
        s = s.substringBefore("/")
        var port: Int? = null
        val idx = s.lastIndexOf(':')
        if (idx > 0) {
            val p = s.substring(idx + 1).toIntOrNull()
            if (p != null && p in 1..65535) {
                port = p
                s = s.substring(0, idx)
            }
        }
        return s.trim() to port
    }

    fun profiles(c: Context): List<PcProfile> {
        migrateLegacy(c)
        val list = mutableListOf<PcProfile>()
        try {
            val arr = JSONArray(sp(c).getString(KEY_PCS, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val (h, pIn) = cleanHost(o.optString("host"))
                if (h.isBlank()) continue
                list.add(
                    PcProfile(
                        id = o.optString("id").ifBlank { newId() },
                        name = o.optString("name").ifBlank { "电脑" },
                        host = h,
                        port = pIn ?: o.optInt("port", 8765),
                        token = o.optString("token"),
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun saveProfiles(c: Context, list: List<PcProfile>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("host", p.host)
                put("port", p.port)
                put("token", p.token)
            })
        }
        sp(c).edit().putString(KEY_PCS, arr.toString()).apply()
    }

    fun isConfigured(c: Context): Boolean = profiles(c).isNotEmpty()

    fun updateRepository(c: Context): String =
        sp(c).getString("update_repository", UpdatePolicy.DEFAULT_REPOSITORY)
            ?.let { runCatching { UpdatePolicy.repository(it) }.getOrNull() }
            ?: UpdatePolicy.DEFAULT_REPOSITORY

    /** 仅接受 owner/repo，不接受任意 URL，也不保存 GitHub 凭据。 */
    fun setUpdateRepository(c: Context, value: String) {
        val repo = UpdatePolicy.repository(value)
        val edit = sp(c).edit().putString("update_repository", repo)
        if (!repo.equals(updateRepository(c), ignoreCase = true)) edit.remove("update_last_notified")
        edit.apply()
    }

    fun autoCheckUpdates(c: Context): Boolean = sp(c).getBoolean("auto_check_updates", true)
    fun setAutoCheckUpdates(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("auto_check_updates", on).apply()
    }

    fun lastNotifiedUpdateVersionCode(c: Context): Long = sp(c).getLong("update_last_notified", 0L)
    fun setLastNotifiedUpdateVersionCode(c: Context, versionCode: Long) {
        sp(c).edit().putLong("update_last_notified", versionCode.coerceAtLeast(0L)).apply()
    }

    fun smsForwardingEnabled(c: Context): Boolean = sp(c).getBoolean("forward_sms", false)
    fun setSmsForwardingEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("forward_sms", on).apply()
    }

    fun notificationForwardingEnabled(c: Context): Boolean = sp(c).getBoolean("forward_notifications", false)
    fun setNotificationForwardingEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("forward_notifications", on).apply()
    }

    /** 手机互连(热点直连)开关与配对码 */
    fun hubEnabled(c: Context): Boolean = sp(c).getBoolean("hub_enabled", false)

    fun setHubEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("hub_enabled", on).apply()
        hubToken(c) // 确保配对码已生成
    }

    fun hubToken(c: Context): String {
        val sp = sp(c)
        var t = sp.getString("hub_token", "").orEmpty()
        if (t.isBlank()) {
            val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
            t = (1..6).map { alphabet[kotlin.random.Random.nextInt(alphabet.length)] }.joinToString("")
            sp.edit().putString("hub_token", t).apply()
        }
        return t
    }

    /** 兼容旧的单台保存接口 */
    fun save(c: Context, host: String, port: Int, token: String, name: String = "电脑") {
        val (h, pIn) = cleanHost(host)
        saveProfiles(c, listOf(PcProfile("pc1", name, h, pIn ?: port, token.trim())))
    }

    /** 旧版本只有单台 host/port/token,首次读取时迁移为配置列表 */
    private fun migrateLegacy(c: Context) {
        val sp = sp(c)
        if (sp.contains(KEY_PCS)) return
        val host = sp.getString("host", "").orEmpty()
        if (host.isBlank()) {
            sp.edit().putString(KEY_PCS, "[]").apply()
            return
        }
        val (h, pIn) = cleanHost(host)
        val profile = PcProfile(
            id = "pc1", name = "电脑", host = h,
            port = pIn ?: sp.getInt("port", 8765),
            token = sp.getString("token", "").orEmpty(),
        )
        saveProfiles(c, listOf(profile))
    }
}
