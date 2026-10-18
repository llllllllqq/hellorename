package moe.hellorename

import java.io.File

/**
 * 缓存清理的规则层：刻意不依赖任何 Android API、也不碰磁盘，方便单元测试（见 CacheCleanerTest）。
 * 磁盘操作在 [CacheStore]，调用方负责放在 IO 线程。
 *
 * 目录约定：
 *   cacheDir/shared/<会话>/<文件名>     被分享出去的副本
 *   cacheDir/shared-state/<会话>        已交付标记（内容 = 交付时刻的毫秒值）
 */
object CacheCleaner {

    /**
     * 未交付会话的存活时间：过了就删。
     *
     * 未交付 = 从没交到第三方 App 手里，没有任何人握有它的 URI；拥有它的界面也已经不在了
     * （还活着的会被活会话集合拦住，见 [CacheState]）。所以只有“刚建好、可能还要重试”这一小段
     * 时间需要保温，10 分钟是给界面重建（配置变更）和用户重试留的余量。
     */
    const val UNDELIVERED_TTL_MS = 10 * 60 * 1000L

    /**
     * 已交付会话的存活时间：从**交付时刻**起算，而不是从复制时刻。
     *
     * 交付出去的 content URI 在接收方自己的任务存活期间一直有效：它可能异步上传，
     * 用户也可能稍后再次转发同一份文件（这会重新读一次我们的缓存）。所以不能像旧实现那样
     * 一律 1 小时后就删。
     */
    const val DELIVERED_TTL_MS = 24 * 60 * 60 * 1000L

    /** 两次清理之间的最小间隔：清理不再只发生在冷启动，但也不该每次分享都全盘扫一遍 */
    const val MIN_CLEANUP_INTERVAL_MS = 10 * 60 * 1000L

    /** 复制前的预留余量上限：不把 /data 写到一滴不剩，给系统和别的 App 留活路 */
    const val SPACE_RESERVE_BYTES = 32L * 1024 * 1024

    /** 未知大小（pipe/恶意 Provider）的流最多复制这么多，防止无限流把设备填满 */
    const val MAX_UNKNOWN_STREAM_BYTES = 2L * 1024 * 1024 * 1024

    /** 一个会话在清理视角下的画像（纯数据，方便单测直接构造） */
    data class Session(
        val name: String,
        /** 目录/文件自身的 mtime：只在直接子项增删或改名时更新 */
        val dirMtime: Long,
        /** 目录内（递归）最新的文件 mtime：只有文件内容变化才会更新 */
        val newestFileMtime: Long,
        /** 已交付时刻（毫秒）；null = 从未交付 */
        val deliveredAt: Long?,
        /** 本进程里还有活着的界面在用它 */
        val live: Boolean,
    ) {
        /**
         * “最后一次有人动它”的时间。
         *
         * 必须取两者的较大者：目录 mtime **不反映**“往文件里写内容”（大文件复制期间一直是开始时刻），
         * 文件 mtime **不反映**“同目录改名”，只看任何一个都会误判。
         */
        val activityAt: Long get() = maxOf(dirMtime, newestFileMtime)
    }

    /**
     * [session] 现在能不能删。判断顺序就是优先级：活着的界面 > 已交付 > 未交付。
     */
    fun shouldDelete(session: Session, now: Long): Boolean {
        // 1) 本进程还有界面在用它：绝不删（旧实现缺的就是这道闸）
        if (session.live) return false

        val deliveredAt = session.deliveredAt
        if (deliveredAt != null) {
            // 2) 交过货：按交付时刻保温。时刻读不出来（<= 0）时不敢删——
            //    宁可让系统按 cacheDir 配额回收，也不能让别人的 URI 变成死链。
            if (deliveredAt <= 0L) return false
            return now - deliveredAt > DELIVERED_TTL_MS
        }

        // 3) 从未交付：没有任何第三方握有它的 URI，拥有它的界面也没了，
        //    只要不是刚建的（界面重建、复制失败后重试）就可以删。
        val activityAt = session.activityAt
        // mtime 读不到（0）时按“早该清了”处理：未交付的目录留着只会白占空间
        if (activityAt <= 0L) return true
        return now - activityAt > UNDELIVERED_TTL_MS
    }

    /**
     * 动态预留量：最多 32MB，但绝不超过剩余空间的 1/4。
     *
     * 旧实现固定要求“剩余 - 文件 >= 32MB”，于是只剩 20MB 的设备连一个 1KB 文件都不让复制。
     * 现在 20MB 只剩 5MB 预留，小文件可以正常复制；空间越紧张，预留也越小。
     */
    fun reserveBytes(freeBytes: Long): Long {
        if (freeBytes <= 0L) return 0L
        return minOf(SPACE_RESERVE_BYTES, freeBytes / 4)
    }

    /**
     * 剩余空间 [freeBytes] 够不够放 [needBytes]。
     *
     * 任一数值不可信（<= 0：pipe 型 Provider 报不出大小、系统读不到剩余空间）就不拦，
     * 交给真正的写入失败兜底——因为读不到数字就不让用户复制，比失败一次更糟。
     */
    fun hasRoomFor(freeBytes: Long, needBytes: Long): Boolean {
        if (needBytes <= 0L) return true
        if (freeBytes <= 0L) return true
        return freeBytes - needBytes >= reserveBytes(freeBytes)
    }

    /**
     * 未知大小流的字节上限：min(2GB, 可写空间 - 动态预留)。
     * 空间读不到（<= 0）时只保留 2GB 硬上限，不能无限写。
     */
    fun maxBytesForUnknownStream(freeBytes: Long): Long {
        if (freeBytes <= 0L) return MAX_UNKNOWN_STREAM_BYTES
        val available = freeBytes - reserveBytes(freeBytes)
        if (available <= 0L) return 0L
        return minOf(MAX_UNKNOWN_STREAM_BYTES, available)
    }

    /** 写入失败是不是“空间不足”引起的（各 ROM 的异常文案不一致，这里按关键词认） */
    fun isNoSpace(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            val message = current.message?.lowercase().orEmpty()
            if (NO_SPACE_MARKERS.any { message.contains(it) }) return true
            current = current.cause
        }
        return false
    }

    private val NO_SPACE_MARKERS = listOf(
        "enospc",
        "no space",
        "space left",
        "not enough space",
        "disk full",
        "quota",
        "insufficient storage",
        "storage full",
    )

    private const val MAX_CAUSE_DEPTH = 5
}

/**
 * 进程级的“活会话”登记与清理节流。
 *
 * 为什么必须有它：旧实现只靠“onCreate 里先清理、再建会话目录”这个**调用顺序**侥幸避开当前会话，
 * 一旦在别处再调一次清理（或用同一个进程里的第二个界面实例）就会删掉别人正在用的文件。
 * 现在“谁在用”由这里说了算，清理一律带排除集。
 */
object CacheState {

    private val liveDirs = mutableSetOf<String>()
    private var lastCleanupAt = 0L

    @Synchronized
    fun register(dir: File) {
        liveDirs.add(dir.absolutePath)
    }

    @Synchronized
    fun unregister(dir: File?) {
        if (dir != null) liveDirs.remove(dir.absolutePath)
    }

    /** 清理开始前取一次快照，之后按这份集合排除 */
    @Synchronized
    fun livePaths(): Set<String> = liveDirs.toSet()

    /**
     * 这次触发要不要真的清理。[force] 用于冷启动与“空间不足先清一次”；
     * 其余触发点按 [CacheCleaner.MIN_CLEANUP_INTERVAL_MS] 节流。
     */
    @Synchronized
    fun shouldCleanup(now: Long, force: Boolean): Boolean {
        // 系统时钟被回拨时 now - lastCleanupAt 会变成负数，旧实现会因此永久节流。
        // 把回拨当成“间隔已到”，重置基准后放行一次。
        if (now < lastCleanupAt) {
            lastCleanupAt = now
            return true
        }
        if (!force && now - lastCleanupAt < CacheCleaner.MIN_CLEANUP_INTERVAL_MS) return false
        lastCleanupAt = now
        return true
    }

    /** 仅供单元测试重置进程级状态 */
    @Synchronized
    fun resetForTest() {
        liveDirs.clear()
        lastCleanupAt = 0L
    }
}
