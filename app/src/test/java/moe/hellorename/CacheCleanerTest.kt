package moe.hellorename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * 缓存清理的测试。
 *
 * 旧实现（Activity 里的 `cleanupOldSessions()`）根本没法测：规则、磁盘操作、生命周期混在一起，
 * 只能靠人肉在设备上攒 1 小时再看。现在规则在 [CacheCleaner]、磁盘层在 [CacheStore]，
 * 都用普通 JUnit + 临时目录直接测。
 */
class CacheCleanerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 固定的“现在”，避免测试依赖真实时钟 */
    private val now = 1_700_000_000_000L

    private lateinit var shared: File
    private lateinit var state: File

    @Before
    fun setUp() {
        shared = tmp.newFolder(CacheStore.SHARED_DIR)
        state = File(tmp.root, CacheStore.STATE_DIR)
        CacheState.resetForTest()
    }

    // ---------- shouldDelete：活会话优先 ----------

    @Test
    fun `live session is never deleted even if ancient`() {
        val session = CacheCleaner.Session(
            name = "1-1",
            dirMtime = 0L,
            newestFileMtime = 0L,
            deliveredAt = null,
            live = true,
        )
        assertFalse(CacheCleaner.shouldDelete(session, now))
    }

    @Test
    fun `registered session survives cleanup until it is released`() {
        val dir = session("1700000000000-1", dirMtime = now - 3 * 60 * 60 * 1000L)
        CacheState.register(dir)

        val kept = CacheStore.cleanup(shared, state, CacheState.livePaths(), now)
        assertTrue("活会话不能删", dir.exists())
        assertEquals(1, kept.keptLive)
        assertEquals(0, kept.deleted)

        // 界面销毁后撤掉登记（MainActivity.onDestroy），下一次清理才轮到它
        CacheState.unregister(dir)
        val cleaned = CacheStore.cleanup(shared, state, CacheState.livePaths(), now)
        assertFalse(dir.exists())
        assertEquals(1, cleaned.deleted)
    }

    // ---------- shouldDelete：未交付 ----------

    @Test
    fun `undelivered session is deleted only after its ttl`() {
        val fresh = CacheCleaner.Session("a", now - 60_000L, 0L, null, false)
        assertFalse(CacheCleaner.shouldDelete(fresh, now))

        val stale = CacheCleaner.Session(
            "b",
            now - CacheCleaner.UNDELIVERED_TTL_MS - 1L,
            0L,
            null,
            false,
        )
        assertTrue(CacheCleaner.shouldDelete(stale, now))
    }

    @Test
    fun `undelivered session with unknown mtime is deleted`() {
        // mtime 读不到（0）时，未交付目录留着只会白占空间
        val unknown = CacheCleaner.Session("x", 0L, 0L, null, false)
        assertTrue(CacheCleaner.shouldDelete(unknown, now))
    }

    @Test
    fun `old directory with a fresh file inside is not deleted`() {
        // 目录 mtime 只在子项增删/改名时更新，往文件里写内容不会更新它：
        // 只看目录 mtime 会把“正在慢慢复制的大文件”当成旧会话删掉
        val session = CacheCleaner.Session(
            name = "copying",
            dirMtime = now - 5 * 60 * 60 * 1000L,
            newestFileMtime = now - 1_000L,
            deliveredAt = null,
            live = false,
        )
        assertFalse(CacheCleaner.shouldDelete(session, now))
    }

    // ---------- shouldDelete：已交付 ----------

    @Test
    fun `delivered session is kept inside its ttl even if the directory looks old`() {
        val session = CacheCleaner.Session(
            name = "sent",
            dirMtime = now - 3 * 60 * 60 * 1000L,
            newestFileMtime = now - 3 * 60 * 60 * 1000L,
            deliveredAt = now - 2 * 60 * 60 * 1000L,
            live = false,
        )
        assertFalse("对方 App 可能还在读，不能按 1 小时删", CacheCleaner.shouldDelete(session, now))
    }

    @Test
    fun `delivered session is deleted after its ttl`() {
        val session = CacheCleaner.Session(
            name = "sent",
            dirMtime = now - 48 * 60 * 60 * 1000L,
            newestFileMtime = now - 48 * 60 * 60 * 1000L,
            deliveredAt = now - CacheCleaner.DELIVERED_TTL_MS - 1L,
            live = false,
        )
        assertTrue(CacheCleaner.shouldDelete(session, now))
    }

    @Test
    fun `delivered session with unreadable timestamp is kept`() {
        val session = CacheCleaner.Session("sent", 0L, 0L, deliveredAt = 0L, live = false)
        assertFalse("读不出交付时刻就不敢删，不能让别人的 URI 变成死链", CacheCleaner.shouldDelete(session, now))
    }

    // ---------- 空间预检 ----------

    @Test
    fun `hasRoomFor keeps a reserve`() {
        val need = 100L * 1024 * 1024
        assertFalse(
            "刚好放得下也不行：必须留出预留量",
            CacheCleaner.hasRoomFor(need + CacheCleaner.SPACE_RESERVE_BYTES - 1L, need),
        )
        assertTrue(CacheCleaner.hasRoomFor(need + CacheCleaner.SPACE_RESERVE_BYTES, need))
        assertTrue("剩余空间读不到时不拦，交给写入失败兜底", CacheCleaner.hasRoomFor(0L, need))
        assertTrue("源大小未知时不拦", CacheCleaner.hasRoomFor(999L, -1L))
    }

    @Test
    fun `isNoSpace recognises the usual messages`() {
        assertTrue(CacheCleaner.isNoSpace(IOException("ENOSPC (No space left on device)")))
        assertTrue(CacheCleaner.isNoSpace(IllegalStateException("write failed: no space left")))
        assertTrue(
            "原因藏在 cause 里也要认出来",
            CacheCleaner.isNoSpace(RuntimeException("copy failed", IOException("ENOSPC"))),
        )
        assertFalse(CacheCleaner.isNoSpace(IOException("connection reset by peer")))
        assertFalse(CacheCleaner.isNoSpace(IOException()))
    }

    // ---------- CacheStore 的磁盘行为 ----------

    @Test
    fun `cleanup deletes stale sessions and prunes their markers`() {
        val stale = session("1700000000000-1", dirMtime = now - 60 * 60 * 1000L)
        val fresh = session("1700000000000-2", dirMtime = now - 60_000L)
        assertTrue(CacheStore.markDelivered(state, stale.name, now - 48 * 60 * 60 * 1000L))

        val report = CacheStore.cleanup(shared, state, emptySet(), now)

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertEquals(1, report.deleted)
        assertEquals(1, report.keptFresh)
        assertEquals(0, report.failed)
        assertTrue("删除会话要算清释放了多少", report.freedBytes > 0L)
        assertNull("会话没了，交付标记也要跟着清掉，否则状态目录会一直涨", CacheStore.deliveredAt(state, stale.name))
    }

    @Test
    fun `cleanup keeps a session delivered two hours ago`() {
        val dir = session("1700000000000-3", dirMtime = now - 3 * 60 * 60 * 1000L)
        assertTrue(CacheStore.markDelivered(state, dir.name, now - 2 * 60 * 60 * 1000L))

        val report = CacheStore.cleanup(shared, state, emptySet(), now)

        assertTrue(dir.exists())
        assertEquals(1, report.keptFresh)
        assertEquals(0, report.deleted)
    }

    @Test
    fun `markDelivered and deliveredAt round trip through the marker file`() {
        assertNull("没有标记就是未交付", CacheStore.deliveredAt(state, "nope"))
        assertTrue(CacheStore.markDelivered(state, "1700000000000-9", now))
        assertEquals(now, CacheStore.deliveredAt(state, "1700000000000-9"))
    }

    @Test
    fun `delivered time comes from marker content not its mtime`() {
        // 标记的 mtime 会被文件系统/备份工具改动，内容才是我们写下的交付时刻
        assertTrue(state.mkdirs() || state.isDirectory)
        val marker = File(state, "s1")
        marker.writeText((now - 100_000L).toString())
        touch(marker, now)
        assertEquals(now - 100_000L, CacheStore.deliveredAt(state, "s1"))
    }

    @Test
    fun `deliveredAt falls back to marker mtime and never claims a bogus time`() {
        assertTrue(state.mkdirs() || state.isDirectory)
        val marker = File(state, "broken")
        marker.writeText("not-a-number")
        touch(marker, now)

        val fallback = CacheStore.deliveredAt(state, "broken")
        assertNotNull("标记内容坏了也必须仍然算「已交付」，否则会被当成未交付提前删掉", fallback)
        // 拿到 0（完全读不出）或 mtime（“刚刚”）都不该被判定成可删
        assertFalse(
            CacheCleaner.shouldDelete(
                CacheCleaner.Session("broken", 0L, 0L, fallback, live = false),
                now,
            ),
        )
    }

    @Test
    fun `stray files in the shared dir are cleaned by the same rules`() {
        // 老版本可能在 shared/ 下留下散落文件：不是目录也不能永远赖着
        val stray = File(shared, "leftover.tmp")
        stray.writeText("x")
        touch(stray, now - 60 * 60 * 1000L)
        val current = File(shared, "fresh.tmp")
        current.writeText("y")
        touch(current, now - 1_000L)

        val report = CacheStore.cleanup(shared, state, emptySet(), now)

        assertFalse(stray.exists())
        assertTrue(current.exists())
        assertEquals(1, report.deleted)
        assertEquals(1, report.keptFresh)
    }

    @Test
    fun `newestFileMtime looks into nested directories`() {
        val dir = File(shared, "1700000000000-4")
        assertTrue(dir.mkdirs())
        val nested = File(dir, "deep")
        assertTrue(nested.mkdirs())
        File(dir, "old.bin").writeText("a")
        touch(File(dir, "old.bin"), now - 1_000L)
        File(nested, "new.bin").writeText("bb")
        touch(File(nested, "new.bin"), now)

        assertEquals(now, CacheStore.newestFileMtime(dir))
        assertEquals(3L, CacheStore.sizeOf(dir))
    }

    @Test
    fun `snapshot reflects live and delivered state`() {
        val live = session("1700000000000-5", dirMtime = now)
        session("1700000000000-6", dirMtime = now)
        assertTrue(CacheStore.markDelivered(state, "1700000000000-6", now - 1000L))

        val snapshot = CacheStore.snapshot(shared, state, setOf(live.absolutePath)).associateBy { it.name }

        assertEquals(true, snapshot.getValue("1700000000000-5").live)
        assertNull(snapshot.getValue("1700000000000-5").deliveredAt)
        assertEquals(false, snapshot.getValue("1700000000000-6").live)
        assertEquals(now - 1000L, snapshot.getValue("1700000000000-6").deliveredAt)
    }

    // ---------- 会话名与节流 ----------

    @Test
    fun `session names stay unique within the same millisecond`() {
        // 只用时间戳时，同一毫秒内的两次复制会共用目录并互相覆盖
        assertEquals("1700000000000-1", CacheStore.newSessionName(now, 1))
        assertTrue(CacheStore.newSessionName(now, 1) != CacheStore.newSessionName(now, 2))
    }

    @Test
    fun `cleanup is throttled but force bypasses the throttle`() {
        assertTrue("冷启动必须清理", CacheState.shouldCleanup(now, force = true))
        assertFalse("紧接着的第二个触发点要被节流", CacheState.shouldCleanup(now + 1_000L, force = false))
        assertTrue(
            CacheState.shouldCleanup(now + CacheCleaner.MIN_CLEANUP_INTERVAL_MS + 1L, force = false),
        )
        assertTrue("空间不足时的强制清理不受节流限制", CacheState.shouldCleanup(now, force = true))
    }

    // ---------- 与 FileProvider 配置的耦合 ----------

    @Test
    fun `shared dir name matches the file provider paths`() {
        // 两处硬编码必须一致：不一致时 FileProvider 会在分享那一刻才抛 IllegalArgumentException，
        // 光看代码看不出来，所以这里刻意**不**用 assumeTrue 跳过——找不到文件就该失败。
        val paths = locateFilePaths()
        assertNotNull("找不到 res/xml/file_paths.xml（单元测试的工作目录变了？）", paths)
        val xml = paths!!.readText()

        assertTrue(
            "CacheStore.SHARED_DIR 必须与 file_paths.xml 里的 <cache-path> 一致",
            xml.contains("path=\"${CacheStore.SHARED_DIR}/\""),
        )
        assertFalse("状态目录不该被 FileProvider 暴露", xml.contains(CacheStore.STATE_DIR))
    }

    /**
     * 从工作目录逐级往上找 file_paths.xml。
     * AGP 单元测试的工作目录是模块目录（app/），但从仓库根跑时也要能找到。
     */
    private fun locateFilePaths(): File? {
        val candidates = listOf(
            "src/main/res/xml/file_paths.xml",
            "app/src/main/res/xml/file_paths.xml",
        )
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val current = dir ?: return null
            for (relative in candidates) {
                val candidate = File(current, relative)
                if (candidate.isFile) return candidate
            }
            dir = current.parentFile
        }
        return null
    }

    /** 造一个会话目录：目录和里面的文件都设成 [mtime] */
    private fun session(name: String, dirMtime: Long, fileName: String = "payload.bin"): File {
        val dir = File(shared, name)
        assertTrue(dir.mkdirs())
        val file = File(dir, fileName)
        file.writeText("payload")
        touch(file, dirMtime)
        touch(dir, dirMtime)
        return dir
    }

    /**
     * 改 mtime 并断言成功：测试环境不支持的话要立刻在这里说清楚，
     * 而不是让后面的“该删/不该删”断言莫名其妙地失败。
     */
    private fun touch(file: File, mtime: Long) {
        assertTrue("测试环境不支持修改 mtime：${file.absolutePath}", file.setLastModified(mtime))
    }
}
