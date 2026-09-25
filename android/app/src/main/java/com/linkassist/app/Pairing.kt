package com.linkassist.app

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Pure JVM validation shared by camera, photo and pasted connection codes. Never resolves DNS. */
object Pairing {
    const val MAX_LENGTH = 2048
    const val VALID_FOR_MILLIS = 5 * 60 * 1000L
    private val fields = setOf("v", "host", "port", "key", "name", "kind")
    private val keyPattern = Regex("[A-Za-z0-9_-]{20,128}")
    private val tokenPattern = Regex("[A-Za-z0-9_-]{6,128}")

    class Payload internal constructor(
        val host: String,
        val port: Int,
        val key: String,
        val name: String,
        val kind: String,
    ) {
        val address: String get() = "$host:$port"
        override fun toString(): String = "Pairing.Payload(host=$host, port=$port, kind=$kind, key=<redacted>)"
    }

    /** Throws only user-safe messages, never the URI or a secret. */
    fun parse(raw: String): Payload {
        require(raw.isNotEmpty() && raw.length <= MAX_LENGTH) { "连接码为空或超过 2048 字符" }
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            throw IllegalArgumentException("连接码格式不正确，请使用 LinkAssist 生成的二维码")
        }
        require(
            !uri.isOpaque && uri.scheme == "linkassist" && uri.rawAuthority == "pair" &&
                uri.rawPath.isNullOrEmpty() && uri.rawFragment == null &&
                uri.rawUserInfo == null && uri.port == -1,
        ) { "仅支持 linkassist://pair 连接码，不会打开网页或其他协议" }

        val query = uri.rawQuery ?: throw IllegalArgumentException("连接码缺少配对信息")
        val values = linkedMapOf<String, String>()
        for (part in query.split('&')) {
            val separator = part.indexOf('=')
            require(separator > 0) { "连接码参数格式不正确" }
            val field = part.substring(0, separator)
            require(field in fields && field !in values) { "连接码含有未知或重复参数" }
            values[field] = decode(part.substring(separator + 1))
        }
        require(values.keys == fields) { "连接码缺少必要参数" }
        require(values.getValue("v") == "1") { "不支持此连接码版本，请更新 LinkAssist" }
        val host = values.getValue("host")
        require(isAllowedHost(host)) { "仅允许局域网或链路本地 IPv4 地址，不能连接公网或回环地址" }
        val portText = values.getValue("port")
        val port = portText.takeIf { it.matches(Regex("[0-9]{1,5}")) }?.toIntOrNull()
        require(port != null && port in 1..65535) { "连接码端口必须为 1–65535" }
        val key = values.getValue("key")
        require(keyPattern.matches(key)) { "连接码密钥格式不正确，请重新生成二维码" }
        val name = values.getValue("name")
        require(isSafeName(name)) { "连接码设备名称无效" }
        val kind = values.getValue("kind")
        require(kind == "pc" || kind == "phone") { "连接码设备类型无效" }
        return Payload(host, port, key, name.trim(), kind)
    }

    /** Canonical decimal IPv4 only: no octal, abbreviated forms, domains, ports or IPv6. */
    fun isAllowedHost(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                (part.length > 1 && part[0] == '0')) return false
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254)
    }

    // Existing installations use six-character tokens; those are not one-time QR secrets.
    fun isSessionToken(token: String): Boolean = tokenPattern.matches(token)

    fun isSafeName(name: String): Boolean = name.isNotBlank() && name.length <= 128 &&
        name.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }

    private fun decode(value: String): String {
        try {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            val out = ByteArrayOutputStream(bytes.size)
            var index = 0
            while (index < bytes.size) {
                if (bytes[index].toInt() == '%'.code) {
                    require(index + 2 < bytes.size)
                    val high = Character.digit(bytes[index + 1].toInt().toChar(), 16)
                    val low = Character.digit(bytes[index + 2].toInt().toChar(), 16)
                    require(high >= 0 && low >= 0)
                    out.write(high * 16 + low)
                    index += 3
                } else {
                    // RFC 3986: '+' is literal, not application/x-www-form-urlencoded space.
                    out.write(bytes[index].toInt())
                    index++
                }
            }
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("连接码包含无效编码")
        }
    }
}
