package moe.hellorename

import java.util.Locale

/**
 * 文件名相关的纯逻辑，刻意不依赖任何 Android API，方便单元测试。
 */
object FileNameUtils {

    const val MAX_EXT_LENGTH = 12
    const val MAX_NAME_LENGTH = 150
    const val MIN_BASE_LENGTH = 20
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
     * 总长度受 [MAX_NAME_LENGTH] 限制，但扩展名永远优先保留。
     */
    fun buildFinalName(rawBase: String, rawExt: String, fallbackBase: String): String {
        val ext = sanitizeExt(rawExt)
        val base = sanitizeBase(rawBase)
            .ifEmpty { sanitizeBase(fallbackBase) }
            .ifEmpty { DEFAULT_BASE }
        val maxBase = (MAX_NAME_LENGTH - ext.length).coerceAtLeast(MIN_BASE_LENGTH)
        return base.take(maxBase) + ext
    }

    /** 给 MimeTypeMap 用的 key（小写、无点） */
    fun extensionKey(ext: String): String = ext.removePrefix(".").lowercase(Locale.US)
}
