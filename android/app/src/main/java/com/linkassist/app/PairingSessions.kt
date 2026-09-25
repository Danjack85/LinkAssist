package com.linkassist.app

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** 一台中心只缓存一个未消费的扫码会话；与长期连接 token 完全独立。 */
internal class PairingSessions(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private data class Session(val key: String, val host: String, val expires: Long)
    private var session: Session? = null

    @Synchronized
    fun payload(host: String, port: Int, name: String): String? {
        if (!isLanIpv4(host) || port !in 1..65535) return null
        val time = now()
        val current = session?.takeIf { it.expires > time && it.host == host }
            ?: Session(randomKey(), host, time + TTL_MS).also { session = it }
        val safeName = name.filterNot { it.isISOControl() }.take(80).ifBlank { "手机" }
        return "linkassist://pair?v=1&host=$host&port=$port&key=${current.key}&name=${URLEncoder.encode(safeName, "UTF-8").replace("+", "%20")}&kind=phone"
    }

    @Synchronized
    fun consume(key: String, deviceName: String): Boolean {
        if (!validKey(key) || deviceName.isBlank() || deviceName.length > 80 || deviceName.any { it.isISOControl() }) return false
        val current = session ?: return false
        if (current.expires <= now()) {
            session = null
            return false
        }
        if (!MessageDigest.isEqual(key.toByteArray(Charsets.US_ASCII), current.key.toByteArray(Charsets.US_ASCII))) return false
        session = null
        return true
    }

    @Synchronized
    fun clear() { session = null }

    companion object {
        const val TTL_MS = 5 * 60 * 1000L
        private val random = SecureRandom()
        private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun randomKey(): String = buildString { repeat(43) { append(alphabet[random.nextInt(alphabet.length)]) } }
        fun validKey(value: String): Boolean = Regex("[A-Za-z0-9_-]{20,128}").matches(value)

        fun isLanIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4 || parts.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) }) return false
            val octets = parts.map { it.toInt() }
            return octets.all { it in 0..255 } && octets[0] in 1..223 && octets[0] != 127 &&
                !(octets[0] == 169 && octets[1] == 254)
        }
    }
}

/** scope 与 transfer id 都匹配时才原子消费；错误请求不能消耗别的接收人的令牌。 */
internal class OneTimeTransferTokens(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private data class Grant(val id: String, val purpose: String, val expires: Long)
    private val grants = HashMap<String, Grant>()

    @Synchronized
    fun issue(id: String, purpose: String): String {
        val time = now()
        grants.entries.removeAll { it.value.expires <= time }
        check(grants.size < 10000) { "临时传输令牌过多" }
        val token = PairingSessions.randomKey()
        grants[token] = Grant(id, purpose, time + 3600_000L)
        return token
    }

    @Synchronized
    fun consume(token: String, id: String, purpose: String): Boolean {
        val grant = grants[token] ?: return false
        if (grant.expires <= now()) { grants.remove(token); return false }
        if (grant.id != id || grant.purpose != purpose) return false
        grants.remove(token)
        return true
    }

    @Synchronized
    fun revoke(id: String) { grants.entries.removeAll { it.value.id == id } }

    @Synchronized
    fun clear() { grants.clear() }
}
