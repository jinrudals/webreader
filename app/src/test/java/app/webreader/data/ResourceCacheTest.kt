package app.webreader.data

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private var max = 100L
    private var protectedSet = emptySet<String>()

    private fun cache(dir: File = tmp.root) = ResourceCache(dir, { max }, { protectedSet })
    private fun res(n: Int, fill: Byte = 1) = CachedResource("text/html", "utf-8", ByteArray(n) { fill })

    @Test fun putThenGet() {
        val c = cache()
        assertTrue(c.put("/a", CachedResource("image/png", null, byteArrayOf(1, 2, 3))))
        val r = c.get("/a")!!
        assertEquals("image/png", r.mimeType)
        assertNull(r.encoding)
        assertArrayEquals(byteArrayOf(1, 2, 3), r.bytes)
        assertEquals(3L, c.sizeBytes())
        assertNull(c.get("/missing"))
    }

    @Test fun evictsLeastRecentlyUsedOverLimit() {
        val c = cache()
        c.put("/a", res(40)); c.put("/b", res(40)); c.put("/c", res(40))
        assertNull(c.get("/a"))
        assertNotNull(c.get("/b"))
        assertNotNull(c.get("/c"))
        assertEquals(80L, c.sizeBytes())
    }

    @Test fun getRefreshesRecency() {
        val c = cache()
        c.put("/a", res(40)); c.put("/b", res(40))
        c.get("/a")
        c.put("/c", res(40))
        assertNotNull(c.get("/a"))
        assertNull(c.get("/b"))
    }

    @Test fun protectedKeysNotEvicted() {
        val c = cache()
        protectedSet = setOf("/a")
        c.put("/a", res(40)); c.put("/b", res(40))
        assertTrue(c.put("/c", res(40)))
        assertNotNull(c.get("/a"))
        assertNull(c.get("/b"))
        assertNotNull(c.get("/c"))
    }

    @Test fun allProtectedAtLimitSkipsPutWithoutLooping() {
        val c = cache()
        protectedSet = setOf("/a", "/b")
        c.put("/a", res(50)); c.put("/b", res(50))
        assertFalse(c.put("/c", res(10)))
        assertNotNull(c.get("/a"))
        assertNotNull(c.get("/b"))
        assertNull(c.get("/c"))
        assertEquals(100L, c.sizeBytes())
    }

    @Test fun oversizedEntryIsRejected() {
        val c = cache()
        c.put("/a", res(10))
        assertFalse(c.put("/big", res(101)))
        assertNotNull(c.get("/a"))
        assertNull(c.get("/big"))
    }

    @Test fun sameKeyPutReplacesAndAdjustsSize() {
        val c = cache()
        c.put("/a", res(60, 1))
        assertTrue(c.put("/a", res(30, 2)))
        assertEquals(30L, c.sizeBytes())
        assertArrayEquals(ByteArray(30) { 2 }, c.get("/a")!!.bytes)
    }

    @Test fun maxBytesChangeAppliesOnNextPut() {
        val c = cache()
        c.put("/a", res(40)); c.put("/b", res(40))
        max = 50
        assertTrue(c.put("/c", res(40)))
        assertNull(c.get("/a"))
        assertNull(c.get("/b"))
        assertNotNull(c.get("/c"))
    }

    @Test fun survivesRestart() {
        val c = cache()
        c.put("/a", res(40)); c.put("/b", res(40))
        c.get("/a")
        val c2 = cache()
        assertEquals(80L, c2.sizeBytes())
        c2.put("/c", res(40))
        assertNotNull(c2.get("/a"))
        assertNull(c2.get("/b"))
    }

    @Test fun clearRemovesAll() {
        val c = cache()
        c.put("/a", res(10)); c.put("/b", res(10))
        c.clear()
        assertEquals(0L, c.sizeBytes())
        assertNull(c.get("/a"))
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun corruptEntryIsMiss() {
        val c = cache()
        c.put("/a", res(10)); c.put("/b", res(10))
        val files = tmp.root.listFiles()!!
        files.first { it.name.endsWith(".bin") }.writeBytes(byteArrayOf(9))
        files.first { it.name.endsWith(".meta") && !File(it.path.removeSuffix(".meta") + ".bin").readBytes().let { b -> b.size == 1 } }
            .writeBytes(byteArrayOf(1, 2))
        val c2 = cache()
        assertNull(c2.get("/a"))
        assertNull(c2.get("/b"))
        assertEquals(0L, c2.sizeBytes())
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun concurrentPutsAndGetsDoNotCorrupt() {
        max = 500
        val c = cache()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (0 until 8).map { t ->
            pool.submit {
                start.await()
                repeat(50) { i ->
                    val k = "/k${(t * 7 + i) % 20}"
                    c.put(k, res(20, (t).toByte()))
                    c.get("/k${i % 20}")
                }
            }
        }
        start.countDown()
        futures.forEach { it.get() }
        pool.shutdown()
        assertTrue(c.sizeBytes() <= c.maxBytes())
        val reloaded = cache()
        assertEquals(c.sizeBytes(), reloaded.sizeBytes())
        (0 until 20).forEach { i ->
            val r = c.get("/k$i")
            if (r != null) assertEquals(20, r.bytes.size)
        }
    }
}
