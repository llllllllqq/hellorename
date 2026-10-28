package moe.hellorename

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/** 一份文件的三个校验值，全部是小写十六进制。 */
data class FileChecksums(
    val md5: String,
    val sha1: String,
    val sha256: String,
)

/**
 * 文件校验值计算。刻意不依赖 Android API，方便 JVM 单测。
 *
 * 只在 IO 线程调用。一次顺序读同时更新 MD5 / SHA-1 / SHA-256，避免为了三个值读三遍。
 * 计算的是**缓存里的最终文件**：如果写盘阶段发生损坏，用户拿这里的值和源文件对比就能发现。
 */
object FileDigestUtils {

    private const val BUFFER_SIZE = 64 * 1024

    /** 非阻塞/异常流连续返回 0 的保护；普通 FileInputStream 不会触发 */
    private const val MAX_ZERO_READS = 100

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * @return 计算结果；[cancelled] 返回 true 时返回 null（调用方按“已取消”处理，不更新 UI）。
     * @throws IOException 读文件失败；调用方应把它当作校验失败，而不是显示半套值。
     */
    fun hashFile(
        file: File,
        cancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): FileChecksums? {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var readTotal = 0L
        var zeroReads = 0

        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (cancelled()) return null
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    zeroReads++
                    if (zeroReads > MAX_ZERO_READS) {
                        throw IOException("file read stalled: ${file.absolutePath}")
                    }
                    Thread.yield()
                    continue
                }
                zeroReads = 0
                md5.update(buffer, 0, read)
                sha1.update(buffer, 0, read)
                sha256.update(buffer, 0, read)
                readTotal += read
                onProgress(readTotal, total)
            }
        }
        if (cancelled()) return null
        return FileChecksums(
            md5 = toHex(md5.digest()),
            sha1 = toHex(sha1.digest()),
            sha256 = toHex(sha256.digest()),
        )
    }

    /** 小写十六进制，长度固定为字节数的两倍。 */
    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }
}
