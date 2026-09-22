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
    fun `buildFinalName never produces a slash`() {
        val name = FileNameUtils.buildFinalName("../../evil", ".sh", "old")
        assertFalse(name.contains('/'))
        assertEquals(".._.._evil.sh", name)
    }

    // ---------- extensionKey ----------

    @Test
    fun `extensionKey is lowercased and dot free`() {
        assertEquals("png", FileNameUtils.extensionKey(".PnG"))
        assertEquals("tar.gz", FileNameUtils.extensionKey(".TAR.GZ"))
        assertEquals("", FileNameUtils.extensionKey(""))
    }
}
