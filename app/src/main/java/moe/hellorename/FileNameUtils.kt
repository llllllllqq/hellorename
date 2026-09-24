package moe.hellorename

import java.util.Locale

/**
 * 文件名相关的纯逻辑，刻意不依赖任何 Android API，方便单元测试。
 */
object FileNameUtils {

    const val MAX_EXT_LENGTH = 12
    /** 名字的字符数上限（可读性考虑；真正的硬限制见 [MAX_NAME_BYTES]） */
    const val MAX_NAME_LENGTH = 150

    /**
     * 单个文件名的**字节**上限。ext4/f2fs 只允许 255 字节，而一个汉字在 UTF-8 里占 3 字节，
     * 只按字符数截断（150 个汉字 = 450 字节）会让 rename/MediaStore 直接 ENAMETOOLONG 失败。
     * 这里留一段余量，给 FUSE/MediaStore 可能追加的后缀（如 ` (1)`）。
     */
    const val MAX_NAME_BYTES = 240
    const val MIN_BASE_LENGTH = 20

    /** 基础名至少保留这么多字节，避免扩展名异常时把名字截成空 */
    const val MIN_BASE_BYTES = 24
    const val DEFAULT_BASE = "file"

    /** FAT/exFAT 不允许的字符 + 控制字符 */
    private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\r\n\t]")

    data class Parts(val base: String, val ext: String)

    /**
     * 拆出基础名与扩展名（扩展名含前导点）。
     * ".nomedia" 这类点开头的文件视为没有扩展名；"a.tar.gz" 取最后一个点。
     */
    fun split(displayName: String): Parts {
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) {
            Parts(displayName.substring(0, dot), displayName.substring(dot))
        } else {
            Parts(displayName, "")
        }
    }

    /** 去掉末尾扩展名（用于“解锁后又重新锁定”的往返） */
    fun stripExt(text: String): String {
        val dot = text.lastIndexOf('.')
        return if (dot > 0) text.substring(0, dot) else text
    }

    /** 基础名清洗：非法字符换成下划线，去掉首尾空白和结尾的点（Windows/Android 都不允许结尾点） */
    fun sanitizeBase(raw: String): String =
        raw.replace(ILLEGAL_CHARS, "_").trim().trimEnd('.', ' ').trim()

    /**
     * 扩展名规范化：必然带一个前导点；非法字符剔除；过长截断；空串表示“不要扩展名”。
     * 同时保证不会产生只有点、或以点结尾的非法名。
     */
    fun sanitizeExt(raw: String): String {
        val cleaned = raw.trim()
            .replace(ILLEGAL_CHARS, "")
            .removePrefix(".")
            .trimStart('.')
            .trimEnd('.')
        if (cleaned.isEmpty()) return ""
        val limited = cleaned.take(MAX_EXT_LENGTH).trimEnd('.')
        return if (limited.isEmpty()) "" else ".$limited"
    }

    /**
     * 拼出最终文件名。基础名为空时回退到 fallbackBase（原文件名），再不行用 "file"。
     * 基础名同时受 [MAX_NAME_LENGTH]（字符）与 [MAX_NAME_BYTES]（UTF-8 字节）限制，
     * 扩展名永远优先保留（它决定对外声明的类型，不能丢也不能被截断）。
     */
    fun buildFinalName(rawBase: String, rawExt: String, fallbackBase: String): String {
        val ext = sanitizeExt(rawExt)
        val base = sanitizeBase(rawBase)
            .ifEmpty { sanitizeBase(fallbackBase) }
            .ifEmpty { DEFAULT_BASE }
        val maxBaseChars = (MAX_NAME_LENGTH - ext.length).coerceAtLeast(MIN_BASE_LENGTH)
        val maxBaseBytes = (MAX_NAME_BYTES - ext.toByteArray(Charsets.UTF_8).size)
            .coerceAtLeast(MIN_BASE_BYTES)
        val limited = truncateUtf8(base.take(maxBaseChars), maxBaseBytes)
        return limited + ext
    }

    /**
     * 按 UTF-8 字节数截断，并且**绝不劈开一个多字节字符**。
     * 直接 `toByteArray().copyOf(n)` 会把汉字/emoji 截成半个字符（变成 U+FFFD）。
     */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var end = maxBytes
        // 0b10xxxxxx 是 UTF-8 的续字节：一直回退到字符边界为止
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    /** 给 MimeTypeMap 用的 key（小写、无点） */
    fun extensionKey(ext: String): String = ext.removePrefix(".").lowercase(Locale.US)
}
