package moe.hellorename

import java.io.File

/**
 * 缓存清理的磁盘层：只认 [java.io.File]，不碰任何 Android API（所以能用普通 JUnit 直接测，
 * 见 CacheCleanerTest）。每个方法都可能做磁盘 IO，**调用方必须放在 IO 线程**。
 *
 * 目录约定（[SHARED_DIR] 必须与 res/xml/file_paths.xml 里的 `shared/` 一致，不一致时分享会
 * 直接抛异常；CacheCleanerTest 里有一条断言把这个耦合钉住）：
 *   cacheDir/shared/<会话>/<文件名>     被分享出去的副本（FileProvider 暴露的就是这个目录）
 *   cacheDir/shared-state/<会话>        已交付标记（内容 = 交付时刻的毫秒值）
 *
 * 标记刻意放在 shared/ 之外：FileProvider 不该暴露状态文件，而且这样 shared/ 下就只有会话，
 * 清理时不用再排除杂项。
 */
object CacheStore {

    const val SHARED_DIR = "shared"
    const val STATE_DIR = "shared-state"

    /** 单次扫描的条目上限：异常目录结构不该把清理拖成无底洞 */
    private const val MAX_SCAN_ENTRIES = 4096

    /** 一次清理的结果，用于日志排障（旧实现把一切吞掉，线上出问题无从下手） */
    data class Report(
        val deleted: Int,
        val freedBytes: Long,
        val keptLive: Int,
        val keptFresh: Int,
        val failed: Int,
    )

    /**
     * 会话名字：毫秒时间戳 + 会话号。
     * 只用时间戳时，同一毫秒内的两次复制会命中同一个目录（`mkdirs` 因目录已存在而“成功”），
     * 两次会话共用 `_incoming<ext>`，后一次会覆盖前一次（可能正被对方 App 读）的文件。
     */
    fun newSessionName(now: Long, session: Int): String = "$now-$session"

    /** shared/ 下的条目：正常是会话目录；老版本遗留的散落文件也算，一并按过期规则处理 */
    fun sessionEntries(sharedRoot: File): List<File> = sharedRoot.listFiles().orEmpty().toList()

    /** 扫描 shared/，给出每个条目的画像（不删任何东西，方便单测直接断言规则） */
    fun snapshot(
        sharedRoot: File,
        stateRoot: File,
        livePaths: Set<String>,
    ): List<CacheCleaner.Session> = sessionEntries(sharedRoot).map { describe(it, stateRoot, livePaths) }

    /**
     * 按 [CacheCleaner.shouldDelete] 的规则删掉过期会话，并顺手清掉已经没有主人的交付标记。
     *
     * [livePaths] 由调用方在开始前从 [CacheState] 取快照：清理期间新注册的会话一定很新，
     * 时间判断本身就会把它留下，所以这份快照够用。
     */
    fun cleanup(
        sharedRoot: File,
        stateRoot: File,
        livePaths: Set<String>,
        now: Long,
    ): Report {
        var deleted = 0
        var freed = 0L
        var keptLive = 0
        var keptFresh = 0
        var failed = 0
        val remaining = mutableSetOf<String>()

        for (entry in sessionEntries(sharedRoot)) {
            val session = describe(entry, stateRoot, livePaths)
            if (!CacheCleaner.shouldDelete(session, now)) {
                if (session.live) keptLive++ else keptFresh++
                remaining.add(entry.name)
                continue
            }
            val size = sizeOf(entry)
            if (entry.deleteRecursively()) {
                deleted++
                freed += size
            } else {
                // 删不掉（文件被占/只读，少见）就留着，标记也不能清
                failed++
                remaining.add(entry.name)
            }
        }

        pruneStateMarkers(stateRoot, remaining)
        return Report(deleted, freed, keptLive, keptFresh, failed)
    }

    /** 目录内（递归）最新的文件 mtime；空目录或读不到时返回 0 */
    fun newestFileMtime(dir: File): Long {
        var newest = 0L
        var visited = 0
        val pending = ArrayDeque<File>()
        pending.addLast(dir)
        while (pending.isNotEmpty() && visited++ < MAX_SCAN_ENTRIES) {
            val children = pending.removeLast().listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) pending.addLast(child)
                else newest = maxOf(newest, child.lastModified())
            }
        }
        return newest
    }

    /** 条目占用的总字节数（删完才知道释放了多少，只用于日志/排障） */
    fun sizeOf(entry: File): Long {
        if (!entry.isDirectory) return entry.length()
        var total = 0L
        var visited = 0
        val pending = ArrayDeque<File>()
        pending.addLast(entry)
        while (pending.isNotEmpty() && visited++ < MAX_SCAN_ENTRIES) {
            val current = pending.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) pending.addLast(child) else total += child.length()
            }
        }
        return total
    }

    /**
     * 记下“这个会话已经交付给对方 App 了”。
     *
     * 这是**跨进程**的保护：内存里的 handedOutPaths 一重启就没了，而这份标记能活到下一次冷启动
     * 的清理，保证交出去的 URI 不会指向一个已经被删掉的文件。
     */
    fun markDelivered(stateRoot: File, session: String, now: Long): Boolean = try {
        if (!stateRoot.isDirectory && !stateRoot.mkdirs()) {
            false
        } else {
            File(stateRoot, session).writeText(now.toString())
            true
        }
    } catch (_: Throwable) {
        false
    }

    /** 交付时刻（毫秒）。没有标记返回 null；标记存在但读不出时刻返回 0（调用方按“不敢删”处理） */
    fun deliveredAt(stateRoot: File, session: String): Long? {
        val marker = File(stateRoot, session)
        if (!marker.isFile) return null
        val text = try {
            marker.readText().trim()
        } catch (_: Throwable) {
            ""
        }
        text.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        val mtime = marker.lastModified()
        return if (mtime > 0L) mtime else 0L
    }

    /** 会话已经没了，它的交付标记也就没意义了（否则状态目录会一直涨） */
    private fun pruneStateMarkers(stateRoot: File, remaining: Set<String>) {
        val markers = stateRoot.listFiles() ?: return
        for (marker in markers) {
            if (marker.name !in remaining) marker.delete()
        }
    }

    private fun describe(
        entry: File,
        stateRoot: File,
        livePaths: Set<String>,
    ): CacheCleaner.Session = CacheCleaner.Session(
        name = entry.name,
        dirMtime = entry.lastModified(),
        newestFileMtime = if (entry.isDirectory) newestFileMtime(entry) else 0L,
        deliveredAt = deliveredAt(stateRoot, entry.name),
        live = entry.absolutePath in livePaths,
    )
}
