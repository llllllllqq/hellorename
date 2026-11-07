package moe.hellorename

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/**
 * Debug-only DocumentsProvider：固定返回
 *
 *   share-root/
 *     a.txt
 *     sub/b.txt
 *
 * 用于 instrumentation 验证文件夹分享的递归展开与目录结构保存。
 * release 构建不会包含它（manifest 在 src/debug）。
 */
class TestFolderProvider : DocumentsProvider() {

    private lateinit var root: File

    override fun onCreate(): Boolean {
        val context = context ?: return false
        root = File(context.cacheDir, "test-folder-provider")
        root.mkdirs()
        File(root, "a.txt").writeText("folder-a")
        File(root, "sub").mkdirs()
        File(root, "sub/b.txt").writeText("folder-b")
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(
            Root.COLUMN_ROOT_ID,
            Document.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Document.COLUMN_MIME_TYPE,
            Root.COLUMN_FLAGS,
        )
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()
        put(row, Root.COLUMN_ROOT_ID, "root")
        put(row, Document.COLUMN_DOCUMENT_ID, "root")
        put(row, Root.COLUMN_TITLE, "share-root")
        put(row, Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
        put(row, Document.COLUMN_FLAGS, 0)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addDocumentRow(cursor, documentId)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        when (parentDocumentId) {
            "root" -> {
                addDocumentRow(cursor, "sub")
                addDocumentRow(cursor, "a.txt")
            }
            "sub" -> addDocumentRow(cursor, "sub/b.txt")
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileFor(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        val id = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Throwable) {
            try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Throwable) {
                return null
            }
        }
        return mimeFor(id)
    }

    private fun addDocumentRow(cursor: MatrixCursor, id: String) {
        val row = cursor.newRow()
        put(row, Document.COLUMN_DOCUMENT_ID, id)
        val displayName = if (id == "root") "share-root" else id.substringAfterLast('/')
        put(row, Document.COLUMN_DISPLAY_NAME, displayName)
        put(row, Document.COLUMN_MIME_TYPE, mimeFor(id))
        put(row, Document.COLUMN_SIZE, if (mimeFor(id) == Document.MIME_TYPE_DIR) 0 else fileFor(id).length())
        put(row, Document.COLUMN_LAST_MODIFIED, 0)
        put(row, Document.COLUMN_FLAGS, 0)
    }

    private fun mimeFor(id: String): String = when (id) {
        "root", "sub" -> Document.MIME_TYPE_DIR
        "a.txt", "sub/b.txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun fileFor(id: String): File = File(root, id)

    private fun put(row: MatrixCursor.RowBuilder, column: String, value: Any?) {
        try {
            row.add(column, value)
        } catch (_: IllegalArgumentException) {
            // projection 没有这一列
        }
    }

    private companion object {
        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
