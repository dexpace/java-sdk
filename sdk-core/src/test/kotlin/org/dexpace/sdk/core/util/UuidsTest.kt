package org.dexpace.sdk.core.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UuidsTest {

    @Test
    fun `version is 4`() {
        repeat(20) {
            assertEquals(4, Uuids.random().version(), "expected RFC 4122 version-4 UUID")
        }
    }

    @Test
    fun `variant is IETF`() {
        repeat(20) {
            // UUID.variant returns 2 for the IETF (RFC 4122) variant.
            assertEquals(2, Uuids.random().variant(), "expected IETF variant")
        }
    }

    @Test
    fun `consecutive calls produce distinct UUIDs`() {
        val a = Uuids.random()
        val b = Uuids.random()
        assertNotEquals(a, b)
    }

    @Test
    fun `concurrent calls across threads produce distinct UUIDs`() {
        val threads = 8
        val perThread = 200
        val seen = ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        try {
            repeat(threads) {
                pool.submit {
                    try {
                        start.await()
                        repeat(perThread) {
                            seen.add(Uuids.random().toString())
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS), "threads did not finish in time")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(threads * perThread, seen.size, "duplicate UUIDs generated under concurrency")
    }

    @Test
    fun `toString matches the canonical UUID format`() {
        val s = Uuids.random().toString()
        // 8-4-4-4-12 hex digits.
        val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue(pattern.matches(s), "unexpected UUID shape: $s")
    }
}
