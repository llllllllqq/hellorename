package moe.hellorename

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

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

    /** 原子写标记留下的临时文件最多保留 1 小时 */
    private const val TEMP_MARKER_TTL_MS = 60 * 60 * 1000L

    /** 一次清理的结果，用于日志排障（旧实现把一切吞掉，线上出问题无从下手） */
    data class Report(
        val deleted: Int,
        val freedBytes: Long,
        val keptLive: Int,
        val keptFresh: Int,
        val failed: Int,
    )

    /**
     * 会话名字：毫秒时间戳 + 会话号 + 随机后缀。
     *
     * 只用时间戳时，同一毫秒内的两次复制会命中同一个目录（`mkdirs` 因目录已存在而“成功”），
     * 两次会话共用 `_incoming<ext>`，后一次会覆盖前一次（可能正被对方 App 读）的文件。
     * 又因为进程重启后会话号会从 0 重新开始，系统时钟回拨时仅靠“时间戳 + 会话号”仍可能撞上
     * 旧目录，所以再加一段 UUID：撞名概率可以忽略。
     */
    fun newSessionName(
        now: Long,
        session: Int,
        token: String = UUID.randomUUID().toString(),
    ): String = "$now-$session-$token"

    /** shared/ 下的条目：正常是会话目录；老版本遗留的散落文件也算，一并按过期规则处理 */
    fun sessionEntries(sharedRoot: File): List<File> =
        sessionEntriesOrNull(sharedRoot).orEmpty()

    /**
     * 与 [sessionEntries] 相同，但保留“读不出来”和“目录为空”的区别。
     * `listFiles()` 返回 null 时绝不能当成空目录——那会把所有交付标记都 prune 掉。
     */
    fun sessionEntriesOrNull(sharedRoot: File): List<File>? = sharedRoot.listFiles()?.toList()

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
     *
     * 整个方法加锁：`force=true` 的清理不受节流限制，两个强制清理并发时不能同时 prune。
     */
    @Synchronized
    fun cleanup(
        sharedRoot: File,
        stateRoot: File,
        livePaths: Set<String>,
        now: Long,
    ): Report {
        // 读不到目录内容（I/O 错误、目录被替换成文件）时直接放弃本次清理：
        // 把 null 当成空目录会清空所有交付标记，破坏对方 App 正在使用的 URI。
        val entries = sessionEntriesOrNull(sharedRoot) ?: return Report(0, 0L, 0, 0, 0)
        var deleted = 0
        var freed = 0L
        var keptLive = 0
        var keptFresh = 0
        var failed = 0
        val seen = mutableSetOf<String>()
        val remaining = mutableSetOf<String>()

        for (entry in entries) {
            seen.add(entry.name)
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

        // 只处理“本次真的列到过、且确实已经不在了”的标记：
        // 清理开始后才新建的会话不会被列到，它的标记绝不能被误删。
        pruneStateMarkers(stateRoot, seen, remaining, now)
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
     *
     * 写入方式：临时文件 → `fsync` → 原子 rename。旧实现直接 `writeText`，进程在写一半时被杀
     * 会留下空文件/半个时间戳；调用方也无法区分“写成功”和“写失败”。
     */
    fun markDelivered(stateRoot: File, session: String, now: Long): Boolean {
        if (!isSafeSessionName(session)) return false
        val temp = File(stateRoot, ".$session.${UUID.randomUUID()}.tmp")
        return try {
            if (!stateRoot.isDirectory && !stateRoot.mkdirs()) {
                false
            } else {
                FileOutputStream(temp).use { output ->
                    output.write(now.toString().toByteArray(Charsets.UTF_8))
                    output.flush()
                    // FileDescriptor.sync() 保证 rename 之前数据已经落到磁盘；
                    // 这样进程被杀也不会留下一个内容为空、却被当成“已交付”的标记。
                    output.fd.sync()
                }
                val marker = File(stateRoot, session)
                if (temp.renameTo(marker)) {
                    true
                } else {
                    temp.delete()
                    false
                }
            }
        } catch (_: Throwable) {
            temp.delete()
            false
        }
    }

    /** 撤销交付标记（分享启动失败时回滚，避免“没交出去却保温 24 小时”） */
    fun clearDelivered(stateRoot: File, session: String): Boolean {
        if (!isSafeSessionName(session)) return false
        return try {
            val marker = File(stateRoot, session)
            !marker.exists() || marker.delete()
        } catch (_: Throwable) {
            false
        }
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

    /**
     * 清理标记：只删 [seen] 里出现过、且不在 [remaining] 里的。
     * 这样“清理开始后才新建的会话”绝不会被误删。
     */
    private fun pruneStateMarkers(
        stateRoot: File,
        seen: Set<String>,
        remaining: Set<String>,
        now: Long,
    ) {
        val markers = stateRoot.listFiles() ?: return
        for (marker in markers) {
            if (marker.name.endsWith(".tmp")) {
                // 原子写标记的临时文件：进程在 rename 前被杀会留下它，过了 1 小时就回收。
                val mtime = marker.lastModified()
                if (mtime > 0L && now - mtime > TEMP_MARKER_TTL_MS) marker.delete()
                continue
            }
            if (marker.name in seen && marker.name !in remaining) marker.delete()
        }
    }

    private fun isSafeSessionName(session: String): Boolean =
        session.isNotEmpty() &&
            session != "." &&
            session != ".." &&
            !session.contains('/') &&
            !session.contains('\\') &&
            !session.contains('\u0000')

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
