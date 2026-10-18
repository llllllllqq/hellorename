package moe.hellorename

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 带上限、可取消的流复制。刻意不依赖 Android API，方便在 JVM 单测里用
 * `ByteArrayInputStream` / 自定义流覆盖 EOF、空流、超限、读 0 等边界。
 *
 * 调用方负责打开/关闭流；本对象只搬运字节。
 */
object StreamCopier {

    const val BUFFER_SIZE = 64 * 1024

    /**
     * 连续读到 0 字节的次数上限。
     * `InputStream.read(byte[])` 在 len > 0 时允许返回 0（非阻塞流），但不能忙等；
     * 让出 CPU 后仍然一直返回 0，就按失败处理，避免复制流程永远卡住。
     */
    private const val MAX_ZERO_READS = 100

    enum class Outcome { OK, TOO_LARGE, CANCELLED, FAILED }

    data class Result(
        val bytes: Long,
        val outcome: Outcome,
        /** [Outcome.FAILED] 时保留底层异常，调用方可以据此识别 ENOSPC */
        val error: IOException? = null,
    ) {
        val isComplete: Boolean get() = outcome == Outcome.OK
    }

    /**
     * 把 [input] 复制到 [output]，最多写 [maxBytes] 字节。
     *
     * @param maxBytes 硬上限；负数表示不限制（只在已知大小且已做空间预检时使用）
     * @param cancelled 每个缓冲区检查一次；返回 true 时立即返回 [Outcome.CANCELLED]
     * @param onProgress 已写入字节数（从 0 开始累计），在调用线程同步触发
     */
    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = -1L,
        cancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ): Result {
        val limit = if (maxBytes < 0L) Long.MAX_VALUE else maxBytes
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        var zeroReads = 0
        return try {
            while (true) {
                if (cancelled()) return Result(total, Outcome.CANCELLED)
                val read = input.read(buffer)
                if (read < 0) return Result(total, Outcome.OK)
                if (read == 0) {
                    zeroReads++
                    if (zeroReads > MAX_ZERO_READS) return Result(total, Outcome.FAILED)
                    // 不忙等：让出时间片，再给流一次机会
                    Thread.yield()
                    continue
                }
                zeroReads = 0
                if (total + read > limit) return Result(total, Outcome.TOO_LARGE)
                output.write(buffer, 0, read)
                total += read
                onProgress(total)
            }
            @Suppress("UNREACHABLE_CODE")
            Result(total, Outcome.OK)
        } catch (error: IOException) {
            Result(total, Outcome.FAILED, error)
        }
    }
}
