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

    /** Android 12+ 的 Toast 最多显示两行，超出的部分会被系统直接截掉 */
    const val TOAST_NAME_MAX_CHARS = 32

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

    /**
     * 基础名清洗：
     *  - FAT/exFAT 非法字符换成下划线；
     *  - 所有 C0/C1 控制字符（含 NUL、`\r\n\t`）换成下划线；
     *  - 双向控制字符直接移除，避免在分享面板里伪装扩展名；
     *  - 去掉首尾空白和结尾的点（Windows/Android 都不允许结尾点）。
     */
    fun sanitizeBase(raw: String): String =
        scrub(raw, replacement = '_').trim().trimEnd('.', ' ').trim()

    /**
     * 扩展名规范化：必然带一个前导点；非法/控制字符剔除；过长截断；空串表示“不要扩展名”。
     * 同时保证不会产生只有点、或以点结尾的非法名。
     */
    fun sanitizeExt(raw: String): String {
        val cleaned = scrub(raw, replacement = null)
            .trim()
            .removePrefix(".")
            .trimStart('.')
            .trimEnd('.')
        if (cleaned.isEmpty()) return ""
        val limited = takeCodePoints(cleaned, MAX_EXT_LENGTH).trimEnd('.')
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
        // 先按 code point 截断，再按 UTF-8 字节截断：两步都不能劈开代理对/多字节字符。
        // 旧实现直接 base.take(n)，当 ASCII 前缀 + emoji 时会在代理对中间切开，编码成 '?'。
        val limited = truncateUtf8(takeCodePoints(base, maxBaseChars), maxBaseBytes)
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

    /**
     * 按 code point 保留前面的 [maxChars] 个字符。
     * 直接用 `String.take` 是按 UTF-16 code unit 计算的，会把 emoji 的代理对切成两半。
     */
    fun takeCodePoints(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        var count = 0
        var index = 0
        while (index < text.length && count < maxChars) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            count++
        }
        return text.substring(0, index)
    }

    /** 按 code point 保留末尾的 [maxChars] 个字符（同样不劈开代理对） */
    fun takeLastCodePoints(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        var count = 0
        var index = text.length
        while (index > 0 && count < maxChars) {
            val codePoint = text.codePointBefore(index)
            index -= Character.charCount(codePoint)
            count++
        }
        return text.substring(index)
    }

    /**
     * Toast 里显示的文件名先掐短：保留首尾（尾部能保住扩展名），中间省略。
     * 按 code point 计算，避免把 emoji 劈成孤立代理项。
     */
    fun ellipsizeForToast(name: String, maxChars: Int = TOAST_NAME_MAX_CHARS): String {
        if (maxChars <= 1) return takeCodePoints(name, maxChars.coerceAtLeast(0))
        if (name.codePointCount(0, name.length) <= maxChars) return name
        val head = (maxChars - 1) / 2
        val tail = maxChars - 1 - head
        return takeCodePoints(name, head) + "…" + takeLastCodePoints(name, tail)
    }

    /** 给 MimeTypeMap 用的 key（小写、无点） */
    fun extensionKey(ext: String): String = ext.removePrefix(".").lowercase(Locale.US)

    /** 是否是需要从文件名里移除的双向控制字符 */
    private fun isBidiControl(codePoint: Int): Boolean =
        codePoint == 0x061C || // ARABIC LETTER MARK
            codePoint == 0x200E || codePoint == 0x200F || // LRM / RLM
            codePoint in 0x202A..0x202E || // LRE / RLE / PDF / LRO / RLO
            codePoint in 0x2066..0x2069 // LRI / RLI / FSI / PDI

    /** FAT 非法字符 + C0/C1 控制字符 */
    private fun isIllegal(codePoint: Int): Boolean = when (codePoint) {
        '\\'.code, '/'.code, ':'.code, '*'.code, '?'.code,
        '"'.code, '<'.code, '>'.code, '|'.code,
        -> true
        // 孤立代理项（非法 UTF-16）编码成 UTF-8 会变成 '?'，一并清掉
        else -> codePoint < 0x20 || codePoint in 0x7F..0x9F || codePoint in 0xD800..0xDFFF
    }

    /**
     * 逐 code point 清洗，保证不会拆开代理对。
     * [replacement] 为 null 时直接删除非法字符（扩展名用），否则替换成该字符（基础名用）。
     */
    private fun scrub(raw: String, replacement: Char?): String = buildString(raw.length) {
        var index = 0
        while (index < raw.length) {
            val codePoint = raw.codePointAt(index)
            val next = index + Character.charCount(codePoint)
            when {
                isBidiControl(codePoint) -> Unit
                isIllegal(codePoint) -> if (replacement != null) append(replacement)
                else -> append(raw, index, next)
            }
            index = next
        }
    }
}
