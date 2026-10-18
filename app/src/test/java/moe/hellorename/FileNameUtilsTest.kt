package moe.hellorename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameUtilsTest {

    // ---------- split ----------

    @Test
    fun `split normal file`() {
        val parts = FileNameUtils.split("holiday.png")
        assertEquals("holiday", parts.base)
        assertEquals(".png", parts.ext)
    }

    @Test
    fun `split keeps multiple dots in base`() {
        val parts = FileNameUtils.split("archive.tar.gz")
        assertEquals("archive.tar", parts.base)
        assertEquals(".gz", parts.ext)
    }

    @Test
    fun `split dotfile has no extension`() {
        val parts = FileNameUtils.split(".nomedia")
        assertEquals(".nomedia", parts.base)
        assertEquals("", parts.ext)
    }

    @Test
    fun `split file without extension`() {
        val parts = FileNameUtils.split("README")
        assertEquals("README", parts.base)
        assertEquals("", parts.ext)
    }

    @Test
    fun `split trailing dot`() {
        val parts = FileNameUtils.split("name.")
        assertEquals("name", parts.base)
        assertEquals(".", parts.ext)
    }

    // ---------- stripExt ----------

    @Test
    fun `stripExt round trip`() {
        assertEquals("my.file", FileNameUtils.stripExt("my.file.png"))
        assertEquals("plain", FileNameUtils.stripExt("plain"))
        assertEquals(".hidden", FileNameUtils.stripExt(".hidden"))
    }

    // ---------- sanitizeBase ----------

    @Test
    fun `sanitizeBase replaces illegal characters`() {
        assertEquals("my_na_me__", FileNameUtils.sanitizeBase("  my/na:me*?  "))
    }

    @Test
    fun `sanitizeBase strips trailing dots and spaces`() {
        assertEquals("report", FileNameUtils.sanitizeBase("report. "))
        assertEquals("report", FileNameUtils.sanitizeBase("  report...  "))
    }

    @Test
    fun `sanitizeBase removes path traversal`() {
        assertEquals(".._.._etc_passwd", FileNameUtils.sanitizeBase("../../etc/passwd"))
        assertEquals("", FileNameUtils.sanitizeBase(".."))
        assertEquals("", FileNameUtils.sanitizeBase("..."))
    }

    @Test
    fun `sanitizeBase keeps unicode and spaces inside`() {
        assertEquals("我的 文件 v2", FileNameUtils.sanitizeBase("我的 文件 v2"))
    }

    // ---------- sanitizeExt ----------

    @Test
    fun `sanitizeExt normalizes leading dot`() {
        assertEquals(".png", FileNameUtils.sanitizeExt("png"))
        assertEquals(".png", FileNameUtils.sanitizeExt(".png"))
        assertEquals(".PNG", FileNameUtils.sanitizeExt("  .PNG "))
    }

    @Test
    fun `sanitizeExt empty cases`() {
        assertEquals("", FileNameUtils.sanitizeExt(""))
        assertEquals("", FileNameUtils.sanitizeExt("."))
        assertEquals("", FileNameUtils.sanitizeExt(".."))
        assertEquals("", FileNameUtils.sanitizeExt("   "))
    }

    @Test
    fun `sanitizeExt drops illegal characters`() {
        assertEquals(".png", FileNameUtils.sanitizeExt("p/n:g"))
        assertEquals(".txt", FileNameUtils.sanitizeExt("t?x<t"))
    }

    @Test
    fun `sanitizeExt truncates long extensions`() {
        val raw = "averyveryverylongextension"
        val ext = FileNameUtils.sanitizeExt(raw)
        assertEquals("." + raw.take(FileNameUtils.MAX_EXT_LENGTH), ext)
        assertFalse(ext.endsWith("."))
    }

    @Test
    fun `sanitizeExt keeps compound extension`() {
        assertEquals(".tar.gz", FileNameUtils.sanitizeExt("tar.gz"))
    }

    // ---------- buildFinalName ----------

    @Test
    fun `buildFinalName simple`() {
        assertEquals("new name.png", FileNameUtils.buildFinalName("new name", ".png", "old"))
    }

    @Test
    fun `buildFinalName falls back to original base`() {
        assertEquals("old.png", FileNameUtils.buildFinalName("", ".png", "old"))
        assertEquals("old.png", FileNameUtils.buildFinalName("...", ".png", "old"))
    }

    @Test
    fun `buildFinalName falls back to default when everything is empty`() {
        assertEquals("file.png", FileNameUtils.buildFinalName("", ".png", ""))
    }

    @Test
    fun `buildFinalName can drop the extension`() {
        assertEquals("noext", FileNameUtils.buildFinalName("noext", "", "old.png"))
    }

    @Test
    fun `buildFinalName keeps extension when base is too long`() {
        val longBase = "x".repeat(500)
        val name = FileNameUtils.buildFinalName(longBase, ".jpeg", "old")
        assertTrue(name.endsWith(".jpeg"))
        assertEquals(FileNameUtils.MAX_NAME_LENGTH, name.length)
    }

    @Test
    fun `buildFinalName keeps a long CJK name within the byte limit`() {
        // 150 个汉字 = 450 字节，只按字符截断会让 rename 直接失败（ext4/f2fs 上限 255 字节）
        val name = FileNameUtils.buildFinalName("很".repeat(150), ".txt", "old")
        assertTrue(name.endsWith(".txt"))
        assertTrue(
            name.toByteArray(Charsets.UTF_8).size <= FileNameUtils.MAX_NAME_BYTES,
        )
        // 不能劈开一个汉字（劈开会产生 U+FFFD 替换字符）
        assertFalse(name.contains('\uFFFD'))
    }

    @Test
    fun `buildFinalName keeps emoji intact within the byte limit`() {
        // 一个 emoji 占 4 字节，按字节截断很容易劈成半个
        val name = FileNameUtils.buildFinalName("🎉".repeat(100), ".png", "old")
        assertTrue(name.endsWith(".png"))
        assertTrue(
            name.toByteArray(Charsets.UTF_8).size <= FileNameUtils.MAX_NAME_BYTES,
        )
        assertFalse(name.contains('\uFFFD'))
    }

    @Test
    fun `buildFinalName leaves a short CJK name alone`() {
        assertEquals("我的照片.png", FileNameUtils.buildFinalName("我的照片", ".png", "old"))
    }

    // ---------- truncateUtf8 ----------

    @Test
    fun `truncateUtf8 cuts on ascii`() {
        assertEquals("abc", FileNameUtils.truncateUtf8("abcdef", 3))
        assertEquals("abcdef", FileNameUtils.truncateUtf8("abcdef", 6))
        assertEquals("abcdef", FileNameUtils.truncateUtf8("abcdef", 99))
        assertEquals("", FileNameUtils.truncateUtf8("abcdef", 0))
    }

    @Test
    fun `truncateUtf8 never splits a multi byte character`() {
        val cjk = "汉字"
        assertEquals("汉", FileNameUtils.truncateUtf8(cjk, 3))
        assertEquals("汉", FileNameUtils.truncateUtf8(cjk, 4))
        assertEquals("汉", FileNameUtils.truncateUtf8(cjk, 5))
        assertEquals("汉字", FileNameUtils.truncateUtf8(cjk, 6))
        assertFalse(FileNameUtils.truncateUtf8(cjk, 4).contains('\uFFFD'))
    }

    @Test
    fun `buildFinalName never produces a slash`() {
        val name = FileNameUtils.buildFinalName("../../evil", ".sh", "old")
        assertFalse(name.contains('/'))
        assertEquals(".._.._evil.sh", name)
    }

    @Test
    fun `ascii prefix followed by emoji is not split into a lone surrogate`() {
        // 旧实现先 base.take(150)：1 个 ASCII + 149 个 UTF-16 code unit = 末尾落在代理对中间，
        // UTF-8 编码后变成 '?'。新实现按 code point 截断。
        val base = "a" + "🎉".repeat(100)
        val name = FileNameUtils.buildFinalName(base, ".png", "old")
        assertTrue(name.endsWith(".png"))
        assertTrue(name.startsWith("a"))
        assertFalse("不能出现 UTF-8 替换字符", name.contains('\uFFFD'))
        assertFalse("不能出现被劈开代理对后编码出的问号", name.contains('?'))
        assertTrue(
            name.toByteArray(Charsets.UTF_8).size <= FileNameUtils.MAX_NAME_BYTES,
        )
    }

    @Test
    fun `control characters are replaced or removed`() {
        assertEquals("a_b_c", FileNameUtils.sanitizeBase("a\u0000b\nc"))
        assertEquals("a_b", FileNameUtils.sanitizeBase("a\u0085b"))
        assertEquals("a_b", FileNameUtils.sanitizeBase("a\u007Fb"))
        // 扩展名里的控制符直接删掉，避免出现 `_png` 这种奇怪后缀
        assertEquals(".png", FileNameUtils.sanitizeExt("p\u0000n\u0085g"))
        // 孤立代理项编码成 UTF-8 会变成 '?'
        assertEquals("a_b", FileNameUtils.sanitizeBase("a\uD800b"))
    }

    @Test
    fun `bidi controls are removed`() {
        assertEquals("invoiceexe", FileNameUtils.sanitizeBase("invoice\u202Eexe"))
        assertEquals("abc", FileNameUtils.sanitizeBase("a\u200Eb\u2066c\u2069"))
        assertEquals(".png", FileNameUtils.sanitizeExt("p\u202Eng"))
    }

    @Test
    fun `takeCodePoints never splits surrogate pairs`() {
        assertEquals("a🎉", FileNameUtils.takeCodePoints("a🎉🎉", 2))
        assertEquals("🎉🎉", FileNameUtils.takeLastCodePoints("a🎉🎉", 2))
        assertFalse(FileNameUtils.takeCodePoints("a🎉🎉", 2).contains('\uFFFD'))
        assertFalse(FileNameUtils.takeLastCodePoints("a🎉🎉", 2).contains('\uFFFD'))
    }

    @Test
    fun `ellipsizeForToast keeps whole emoji`() {
        val name = "a" + "🎉".repeat(40) + ".png"
        val short = FileNameUtils.ellipsizeForToast(name, maxChars = 20)
        assertTrue(short.contains('…'))
        assertFalse(short.contains('\uFFFD'))
        assertTrue(short.endsWith(".png"))
        assertTrue(short.codePointCount(0, short.length) <= 20)
    }

    // ---------- extensionKey ----------

    @Test
    fun `extensionKey is lowercased and dot free`() {
        assertEquals("png", FileNameUtils.extensionKey(".PnG"))
        assertEquals("tar.gz", FileNameUtils.extensionKey(".TAR.GZ"))
        assertEquals("", FileNameUtils.extensionKey(""))
    }
}
