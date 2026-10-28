package moe.hellorename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FileDigestUtilsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `empty file has the well known hashes`() {
        val file = tmp.newFile("empty.bin")
        val checksums = FileDigestUtils.hashFile(file)!!
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", checksums.md5)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", checksums.sha1)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb924" +
                "27ae41e4649b934ca495991b7852b855",
            checksums.sha256,
        )
    }

    @Test
    fun `abc has the well known hashes`() {
        val file = tmp.newFile("abc.txt")
        file.writeText("abc")
        val checksums = FileDigestUtils.hashFile(file)!!
        assertEquals("900150983cd24fb0d6963f7d28e17f72", checksums.md5)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", checksums.sha1)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223" +
                "b00361a396177a9cb410ff61f20015ad",
            checksums.sha256,
        )
    }

    @Test
    fun `all three values are lowercase hex of the right length`() {
        val file = tmp.newFile("binary.bin")
        file.writeBytes(ByteArray(4096) { (it * 31 and 0xFF).toByte() })
        val checksums = FileDigestUtils.hashFile(file)!!
        assertEquals(32, checksums.md5.length)
        assertEquals(40, checksums.sha1.length)
        assertEquals(64, checksums.sha256.length)
        assertTrue(checksums.md5.all { it in "0123456789abcdef" })
        assertTrue(checksums.sha1.all { it in "0123456789abcdef" })
        assertTrue(checksums.sha256.all { it in "0123456789abcdef" })
    }

    @Test
    fun `progress reaches the file length`() {
        val file = tmp.newFile("progress.bin")
        file.writeBytes(ByteArray(200_000) { 7 })
        var lastRead = 0L
        var lastTotal = -1L
        val checksums = FileDigestUtils.hashFile(file) { read, total ->
            lastRead = read
            lastTotal = total
        }!!
        assertEquals(200_000L, lastRead)
        assertEquals(200_000L, lastTotal)
        assertTrue(checksums.sha256.isNotEmpty())
    }

    @Test
    fun `cancelled hash returns null`() {
        val file = tmp.newFile("cancel.bin")
        file.writeBytes(ByteArray(200_000) { 3 })
        assertNull(FileDigestUtils.hashFile(file, cancelled = { true }))
    }

    @Test
    fun `toHex pads bytes correctly`() {
        assertEquals("00010f10ff", FileDigestUtils.toHex(byteArrayOf(0, 1, 15, 16, -1)))
    }

    @Test
    fun `missing file throws IOException`() {
        val missing = File(tmp.root, "missing.bin")
        try {
            FileDigestUtils.hashFile(missing)
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
            // expected
        }
    }
}
