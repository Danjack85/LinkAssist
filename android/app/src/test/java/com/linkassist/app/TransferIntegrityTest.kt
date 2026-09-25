package com.linkassist.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

class TransferIntegrityTest {
    private val bytes = "LinkAssist integrity test".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun withPart(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("linkassist-integrity-").toFile()
        try { block(File(dir, "file.part")) } finally { dir.deleteRecursively() }
    }

    private fun failsWithoutPartial(part: File, block: () -> Unit) {
        try { block(); fail("Expected IOException") } catch (_: IOException) { }
        assertFalse("Failed transfer left a .part", part.exists())
    }

    @Test fun streamsMatchingFileAndSupportsUppercaseHash() = withPart { part ->
        assertEquals(hash, TransferIntegrity.writeVerified(ByteArrayInputStream(bytes), part, bytes.size.toLong(), hash.uppercase()))
        assertArrayEquals(bytes, part.readBytes())
        assertEquals(hash, TransferIntegrity.sha256(part))
    }

    @Test fun shortOversizedAndWrongHashRemovePartial() = withPart { part ->
        failsWithoutPartial(part) { TransferIntegrity.writeVerified(ByteArrayInputStream(bytes), part, bytes.size + 1L, hash) }
        failsWithoutPartial(part) { TransferIntegrity.writeVerified(ByteArrayInputStream(bytes), part, bytes.size - 1L, hash) }
        failsWithoutPartial(part) { TransferIntegrity.writeVerified(ByteArrayInputStream(bytes), part, bytes.size.toLong(), "0".repeat(64)) }
    }

    @Test fun cancelledAndBrokenStreamsRemovePartial() = withPart { part ->
        failsWithoutPartial(part) { TransferIntegrity.writeVerified(ByteArrayInputStream(bytes), part, bytes.size.toLong(), hash, cancelled = { true }) }
        val broken = object : InputStream() { override fun read(): Int = throw IOException("connection dropped") }
        failsWithoutPartial(part) { TransferIntegrity.writeVerified(broken, part, bytes.size.toLong(), hash) }
    }

    @Test fun zeroByteTransferIsValidAndInvalidMetadataIsRejected() = withPart { part ->
        val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        TransferIntegrity.writeVerified(ByteArrayInputStream(ByteArray(0)), part, 0L, emptyHash)
        assertEquals(0L, part.length())
        failsWithoutPartial(part) { TransferIntegrity.writeVerified(ByteArrayInputStream(bytes), part, -1L, hash) }
        failsWithoutPartial(part) { TransferIntegrity.writeVerified(ByteArrayInputStream(bytes), part, bytes.size.toLong(), "") }
    }

    @Test fun limitsBodiesEvenWithoutContentLength() {
        assertArrayEquals(bytes, TransferIntegrity.readLimited(ByteArrayInputStream(bytes), bytes.size))
        try {
            TransferIntegrity.readLimited(ByteArrayInputStream(bytes), bytes.size - 1)
            fail("Expected body limit")
        } catch (_: IOException) { }
    }

    @Test fun normalizesUnsafeDownloadNames() {
        assertEquals("received-file", TransferIntegrity.safeName(".."))
        assertFalse(TransferIntegrity.safeName("../file\n.apk").contains('/'))
        assertFalse(TransferIntegrity.safeName("../file\n.apk").contains('\n'))
    }
}
