package moe.hellorename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class StreamCopierTest {

    @Test
    fun `copies until eof`() {
        val data = "hello world".toByteArray()
        val output = ByteArrayOutputStream()
        val result = StreamCopier.copy(ByteArrayInputStream(data), output)
        assertEquals(StreamCopier.Outcome.OK, result.outcome)
        assertEquals(data.size.toLong(), result.bytes)
        assertEquals("hello world", output.toString(Charsets.UTF_8.name()))
        assertTrue(result.isComplete)
    }

    @Test
    fun `empty stream is a successful empty file`() {
        val output = ByteArrayOutputStream()
        val result = StreamCopier.copy(ByteArrayInputStream(ByteArray(0)), output)
        assertEquals(StreamCopier.Outcome.OK, result.outcome)
        assertEquals(0L, result.bytes)
        assertEquals(0, output.size())
    }

    @Test
    fun `stream over the limit fails without writing past it`() {
        val data = ByteArray(10) { it.toByte() }
        val output = ByteArrayOutputStream()
        val result = StreamCopier.copy(ByteArrayInputStream(data), output, maxBytes = 5L)
        assertEquals(StreamCopier.Outcome.TOO_LARGE, result.outcome)
        assertTrue(result.bytes <= 5L)
        assertTrue(output.size() <= 5)
    }

    @Test
    fun `zero bytes returned once is not treated as eof`() {
        val data = "abc".toByteArray()
        val output = ByteArrayOutputStream()
        val result = StreamCopier.copy(ZeroOnceStream(data), output)
        assertEquals(StreamCopier.Outcome.OK, result.outcome)
        assertEquals("abc", output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `a stream that only returns zero eventually fails instead of spinning`() {
        val output = ByteArrayOutputStream()
        val result = StreamCopier.copy(AlwaysZeroStream(), output)
        assertEquals(StreamCopier.Outcome.FAILED, result.outcome)
        assertEquals(0L, result.bytes)
    }

    @Test
    fun `cancellation stops before the next buffer`() {
        val data = ByteArray(200_000) { 1 }
        var checks = 0
        val output = ByteArrayOutputStream()
        val result = StreamCopier.copy(
            ByteArrayInputStream(data),
            output,
            cancelled = { checks++ > 0 },
        )
        assertEquals(StreamCopier.Outcome.CANCELLED, result.outcome)
        assertTrue("取消后不能再无限写", output.size() < data.size)
    }

    @Test
    fun `write failure keeps the underlying io exception`() {
        val result = StreamCopier.copy(
            ByteArrayInputStream("abc".toByteArray()),
            FailingOutputStream(),
        )
        assertEquals(StreamCopier.Outcome.FAILED, result.outcome)
        assertNotNull("调用方需要靠异常文案识别 ENOSPC", result.error)
        assertTrue(result.error is IOException)
    }

    private class ZeroOnceStream(private val data: ByteArray) : InputStream() {
        private var zeroReturned = false
        private var offset = 0

        override fun read(): Int {
            if (offset >= data.size) return -1
            return data[offset++].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (!zeroReturned) {
                zeroReturned = true
                return 0
            }
            if (offset >= data.size) return -1
            val count = minOf(len, data.size - offset)
            System.arraycopy(data, offset, buffer, off, count)
            offset += count
            return count
        }
    }

    private class AlwaysZeroStream : InputStream() {
        override fun read(): Int = 0
        override fun read(buffer: ByteArray, off: Int, len: Int): Int = 0
    }

    private class FailingOutputStream : OutputStream() {
        override fun write(b: Int) = throw IOException("ENOSPC")
        override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("ENOSPC")
    }
}
