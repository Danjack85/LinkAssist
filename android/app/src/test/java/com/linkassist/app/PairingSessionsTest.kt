package com.linkassist.app

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PairingSessionsTest {
    private fun key(payload: String): String = URI(payload).rawQuery.split('&').single { it.startsWith("key=") }.substringAfter('=')

    @Test fun cachesUntilConsumedThenGeneratesNewKey() {
        val sessions = PairingSessions { 100L }
        val first = sessions.payload("192.168.1.2", 8765, "中心 手机")!!
        assertTrue(first.startsWith("linkassist://pair?v=1&host=192.168.1.2&port=8765&key="))
        assertTrue(first.endsWith("&kind=phone"))
        assertFalse(first.contains("token="))
        assertEquals(first, sessions.payload("192.168.1.2", 8765, "中心 手机"))
        assertTrue(PairingSessions.validKey(key(first)))
        assertTrue(sessions.consume(key(first), "phone"))
        assertFalse(sessions.consume(key(first), "phone"))
        assertNotEquals(key(first), key(sessions.payload("192.168.1.2", 8765, "中心 手机")!!))
    }

    @Test fun expiresAtFiveMinutesAndHostChangesInvalidateOldKey() {
        var now = 0L
        val sessions = PairingSessions { now }
        val first = sessions.payload("192.168.1.2", 8765, "hub")!!
        now = PairingSessions.TTL_MS
        assertFalse(sessions.consume(key(first), "phone"))
        val second = sessions.payload("192.168.1.2", 8765, "hub")!!
        assertNotEquals(key(first), key(second))
        val third = sessions.payload("192.168.2.2", 8765, "hub")!!
        assertFalse(sessions.consume(key(second), "phone"))
        assertTrue(sessions.consume(key(third), "phone"))
    }

    @Test fun invalidFieldsDoNotConsumeValidSession() {
        val sessions = PairingSessions { 0L }
        val token = key(sessions.payload("192.168.1.2", 8765, "hub")!!)
        assertFalse(sessions.consume("short", "phone"))
        assertFalse(sessions.consume("!".repeat(43), "phone"))
        assertFalse(sessions.consume(token, ""))
        assertFalse(sessions.consume(token, "x".repeat(81)))
        assertFalse(sessions.consume(token, "phone\n"))
        assertTrue(sessions.consume(token, "phone"))
        for (host in listOf("0.0.0.0", "127.0.0.1", "::1", "example.org", "192.168.1.999", "224.0.0.1", "192.168.01.1")) {
            assertNull(sessions.payload(host, 8765, "hub"))
        }
    }

    @Test fun concurrentReplayHasOnlyOneWinner() {
        val sessions = PairingSessions { 0L }
        val token = key(sessions.payload("192.168.1.2", 8765, "hub")!!)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..16).map { pool.submit<Boolean> { start.await(); sessions.consume(token, "phone") } }
            start.countDown()
            assertEquals(1, results.count { it.get(3, TimeUnit.SECONDS) })
        } finally { pool.shutdownNow() }
    }

    @Test fun downloadGrantsAreIndependentScopedAndOneTime() {
        val tokens = OneTimeTransferTokens { 0L }
        val a = tokens.issue("file", "download")
        val b = tokens.issue("file", "download")
        val uploadResponse = tokens.issue("file", "download")
        assertEquals(3, setOf(a, b, uploadResponse).size)
        assertFalse(tokens.consume(a, "other-file", "download"))
        assertFalse(tokens.consume(a, "file", "upload"))
        assertTrue(tokens.consume(a, "file", "download"))
        assertFalse(tokens.consume(a, "file", "download"))
        assertTrue(tokens.consume(b, "file", "download"))
        assertTrue(tokens.consume(uploadResponse, "file", "download"))
    }

    @Test fun expiredAndRevokedTransferTokensCannotBeUsed() {
        var now = 0L
        val tokens = OneTimeTransferTokens { now }
        val expired = tokens.issue("a", "download")
        now = 3600_000L
        assertFalse(tokens.consume(expired, "a", "download"))
        val revoked = tokens.issue("a", "upload")
        val retained = tokens.issue("b", "upload")
        tokens.revoke("a")
        assertFalse(tokens.consume(revoked, "a", "upload"))
        assertTrue(tokens.consume(retained, "b", "upload"))
    }
}
