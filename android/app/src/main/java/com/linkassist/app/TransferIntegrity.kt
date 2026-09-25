package com.linkassist.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest

internal object TransferIntegrity {
    const val MAX_FILE_BYTES = 512L * 1024 * 1024

    fun validSha256(value: String): Boolean = Regex("[0-9a-fA-F]{64}").matches(value)

    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size() + 1))
            if (n < 0) break
            if (n == 0) continue
            if (out.size() + n > limit) throw IOException("响应体超过 ${limit / 1024} KiB 限制")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 始终流式计算摘要；任何错误（包括取消、磁盘写失败）都删除临时文件。 */
    fun writeVerified(
        input: InputStream,
        part: File,
        expectedSize: Long,
        expectedSha256: String? = null,
        cancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ): String {
        try {
            if (expectedSize !in 0..MAX_FILE_BYTES) throw IOException("文件大小无效或超过 512 MiB")
            if (expectedSha256 != null && !validSha256(expectedSha256)) throw IOException("缺少有效 SHA-256，无法验证文件")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileOutputStream(part).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("传输已取消")
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    total += n
                    if (total > expectedSize) throw IOException("文件大小超过声明值")
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    onProgress(total)
                }
                if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("传输已取消")
                if (total != expectedSize) throw IOException("文件大小校验失败 ($total != $expectedSize)")
                out.fd.sync()
            }
            val actual = hex(digest.digest())
            if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) throw IOException("SHA-256 校验失败")
            return actual
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) digest.update(buf, 0, n)
            }
        }
        return hex(digest.digest())
    }

    fun safeName(raw: String): String = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim().trim('.').take(120).ifBlank { "received-file" }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
