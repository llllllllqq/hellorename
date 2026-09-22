package moe.hellorename

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.hellorename.databinding.ActivityMainBinding
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * 分享接收 → 改名 → 再分享。全应用零权限、纯前台 Activity。
 *
 * 关键约束（决定了实现方式）：
 *  - 别的应用分享进来的 content:// URI 是“临时且不可转发”的授权，我们既不能原地改名，
 *    也不能把授权转给下一个应用，所以必须先把数据复制成自己的。
 *  - 复制只做一次：cacheDir/shared/<会话>/_incoming<ext>；改名时同目录 rename，零字节拷贝。
 *  - 一旦某个路径已经交给系统分享面板，就再也不能 rename/删除它（目标应用可能正在异步读取），
 *    因此再次改名要改成复制，见 [handedOutPaths]。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var sourceUri: Uri? = null
    private var sourceMime: String? = null
    private var originalName: String = ""
    private var originalBase: String = ""
    private var originalExt: String = ""   // 含前导 '.'，无扩展名时为空串

    private var sessionDir: File? = null
    private var currentFile: File? = null  // 缓存里代表“当前文件名”的那个文件
    private var copyJob: Job? = null
    private var copyDone = false

    /** 已经交给系统分享面板的绝对路径，不允许再被 rename/删除 */
    private val handedOutPaths = mutableSetOf<String>()

    private var lastPercent = -1
    private var lastReportedBytes = 0L

    /** null = 扩展名可编辑；非 null = 扩展名锁定为该值（可能是空串） */
    private var lockedExt: String? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // SAF 的授权可以持久化，顺手记下来（普通分享的 URI 会抛异常，忽略即可）
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Throwable) {
                // ignore
            }
            loadSource(uri, extraCount = 1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.sendButton.setOnClickListener { onSendClicked() }
        binding.pickButton.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        binding.allowExtCheck.setOnCheckedChangeListener { _, isChecked -> applyExtLock(!isChecked) }

        cleanupOldSessions()
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    // ---------------------------------------------------------------- 入口解析

    private fun handleShareIntent(intent: Intent?) {
        val action = intent?.action
        val isShare = action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
        val uris = if (isShare) collectUris(intent) else emptyList()

        when {
            uris.isNotEmpty() -> loadSource(uris.first(), uris.size)
            isShare -> {
                showEmptyState()
                setStatus(getString(R.string.status_text_only), isError = true)
            }
            else -> showEmptyState()
        }
    }

    private fun collectUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { out.add(it) }
            }
        }
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
        return out.filter { it.scheme == "content" || it.scheme == "file" }
    }

    private fun loadSource(uri: Uri, extraCount: Int) {
        copyJob?.cancel()
        copyJob = null
        copyDone = false
        currentFile = null
        handedOutPaths.clear()

        sourceUri = uri
        sourceMime = try {
            contentResolver.getType(uri)
        } catch (_: Throwable) {
            null
        }

        var display = queryDisplayName(uri)
        if (display.isBlank()) display = getString(R.string.fallback_name)
        originalName = display
        val parts = FileNameUtils.split(display)
        originalBase = parts.base
        originalExt = parts.ext

        binding.oldNameText.text = originalName
        binding.pickButton.visibility = View.GONE

        // 默认锁定扩展名
        lockedExt = originalExt
        binding.allowExtCheck.setOnCheckedChangeListener(null)
        binding.allowExtCheck.isChecked = false
        binding.allowExtCheck.isEnabled = originalExt.isNotEmpty()
        binding.allowExtCheck.setOnCheckedChangeListener { _, isChecked -> applyExtLock(!isChecked) }
        binding.nameInputLayout.suffixText = originalExt.ifEmpty { null }
        setInput(originalBase)
        updateHint()

        if (extraCount > 1) {
            setStatus(
                resources.getQuantityString(R.plurals.status_multi, extraCount, extraCount),
                isError = false,
            )
        } else {
            setStatus(getString(R.string.status_copying), isError = false)
        }

        startCopy(uri)
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = ""
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) name = cursor.getString(index).orEmpty()
                    }
                }
        } catch (_: Throwable) {
            // 走下面的兜底
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        }
        return name.trim()
    }

    // ---------------------------------------------------------------- 复制到缓存

    private fun startCopy(uri: Uri) {
        val dir = File(File(cacheDir, SHARED_DIR), System.currentTimeMillis().toString())
        if (!dir.exists() && !dir.mkdirs()) {
            setStatus(getString(R.string.status_copy_failed), isError = true)
            return
        }
        val target = File(dir, INCOMING_NAME + originalExt)
        sessionDir = dir
        currentFile = target
        copyDone = false
        lastPercent = -1
        lastReportedBytes = 0L

        binding.progress.isIndeterminate = true
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        binding.sendButton.isEnabled = false

        copyJob = lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                copyUriToFile(uri, target) { copied, total -> onCopyProgress(copied, total) }
            }
            if (!isActive) return@launch
            binding.progress.visibility = View.GONE
            copyDone = ok
            binding.sendButton.isEnabled = ok
            if (ok) {
                setStatus(getString(R.string.status_ready), isError = false)
            } else {
                currentFile = null
                setStatus(getString(R.string.status_copy_failed), isError = true)
            }
        }
    }

    /** 复制进度回调，来自 IO 线程，已做节流 */
    private fun onCopyProgress(copied: Long, total: Long) {
        if (total <= 0L) {
            if (copied - lastReportedBytes < PROGRESS_BYTE_STEP) return
            lastReportedBytes = copied
            val text = getString(R.string.status_copying_size, formatSize(copied))
            runOnUiThread { setStatus(text, isError = false) }
            return
        }
        val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
        if (percent == lastPercent) return
        lastPercent = percent
        runOnUiThread {
            binding.progress.isIndeterminate = false
            binding.progress.progress = percent
            setStatus(getString(R.string.status_copying_percent, percent), isError = false)
        }
    }

    /**
     * 复制源 URI 到本地文件。
     *
     * 快路径用 file descriptor + FileChannel.transferTo；因为 transferTo 允许“少传”甚至返回 0，
     * 所以必须用 statSize 校验字节数，对不上就整份丢弃、改走流复制，避免静默截断。
     */
    private fun copyUriToFile(uri: Uri, target: File, onProgress: (Long, Long) -> Unit): Boolean {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val statSize = try {
                    pfd.statSize
                } catch (_: Throwable) {
                    -1L
                }
                FileInputStream(pfd.fileDescriptor).use { input ->
                    FileOutputStream(target).use { output ->
                        val inChannel = input.channel
                        val outChannel = output.channel
                        var position = 0L
                        var total = 0L
                        var stalls = 0
                        while (true) {
                            val moved = inChannel.transferTo(position, CHUNK_SIZE, outChannel)
                            if (moved <= 0L) {
                                if (++stalls >= MAX_STALLS) break
                                continue
                            }
                            stalls = 0
                            position += moved
                            total += moved
                            onProgress(total, statSize)
                        }
                        if (statSize >= 0L && total == statSize) return true
                        if (statSize < 0L && total > 0L) return true
                    }
                }
            }
        } catch (_: Throwable) {
            // 落到下面的流复制
        }
        target.delete()

        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onProgress(total, -1L)
                    }
                }
                true
            } ?: false
        } catch (_: Throwable) {
            target.delete()
            false
        }
    }

    /** 同目录复制（只在“已经分享过的文件”需要改名时使用，避免打破对方正在读的 URI） */
    private fun copyFile(source: File, target: File): Boolean = try {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                var position = 0L
                while (true) {
                    val moved = input.channel.transferTo(position, CHUNK_SIZE, output.channel)
                    if (moved <= 0L) break
                    position += moved
                }
            }
        }
        true
    } catch (_: Throwable) {
        target.delete()
        false
    }

    // ---------------------------------------------------------------- 改名 + 分享

    private fun onSendClicked() {
        val uri = sourceUri ?: run {
            setStatus(getString(R.string.status_no_file), isError = true)
            return
        }
        val file = currentFile
        if (!copyDone || file == null || !file.exists()) {
            setStatus(getString(R.string.status_copying), isError = false)
            startCopy(uri)
            return
        }

        val (rawBase, rawExt) = currentNameParts()
        val finalName = FileNameUtils.buildFinalName(rawBase, rawExt, originalBase)
        val target = File(sessionDir ?: file.parentFile, finalName)

        if (target.absolutePath != file.absolutePath) {
            val succeeded = if (handedOutPaths.contains(file.absolutePath)) {
                // 旧名字已经给出去过了，不能动它，改成复制
                copyFile(file, target)
            } else {
                if (target.exists()) target.delete()
                file.renameTo(target)
            }
            if (!succeeded) {
                setStatus(getString(R.string.status_rename_failed), isError = true)
                return
            }
        }
        currentFile = target
        handedOutPaths.add(target.absolutePath)

        val shareUri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        } catch (_: Throwable) {
            setStatus(getString(R.string.status_rename_failed), isError = true)
            return
        }

        val mime = resolveMime(rawExt)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_TITLE, finalName)
            clipData = ClipData.newUri(contentResolver, finalName, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(sendIntent, getString(R.string.chooser_title)))
            if (rawExt.equals(originalExt, ignoreCase = true)) {
                setStatus(getString(R.string.status_sent), isError = false)
            } else {
                setStatus(getString(R.string.status_ext_changed), isError = false)
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_share_app, Toast.LENGTH_LONG).show()
        }
    }

    /** 当前输入对应的 (基础名, 扩展名)；扩展名锁定时不信任输入框里的后缀 */
    private fun currentNameParts(): Pair<String, String> {
        val raw = binding.nameInput.text?.toString().orEmpty()
        val locked = lockedExt
        if (locked != null) {
            val base = raw.trim()
            return (base.ifEmpty { originalBase }) to locked
        }
        val name = raw.trim()
        if (name.isEmpty()) return originalBase to originalExt
        val parts = FileNameUtils.split(name)
        return parts.base to parts.ext
    }

    private fun resolveMime(ext: String): String {
        val key = FileNameUtils.extensionKey(ext)
        val fromExt = if (key.isEmpty()) {
            null
        } else {
            try {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(key)
            } catch (_: Throwable) {
                null
            }
        }
        return if (ext.equals(originalExt, ignoreCase = true)) {
            sourceMime ?: fromExt ?: FALLBACK_MIME
        } else {
            fromExt ?: FALLBACK_MIME
        }
    }

    // ---------------------------------------------------------------- 扩展名锁

    private fun applyExtLock(lock: Boolean) {
        val raw = binding.nameInput.text?.toString().orEmpty()
        val previous = lockedExt
        val base = if (previous != null) raw else FileNameUtils.stripExt(raw)
        if (lock) {
            lockedExt = originalExt
            binding.nameInputLayout.suffixText = originalExt.ifEmpty { null }
            setInput(base)
        } else {
            val ext = previous ?: originalExt
            lockedExt = null
            binding.nameInputLayout.suffixText = null
            setInput(base + ext)
        }
        updateHint()
    }

    private fun updateHint() {
        binding.hintText.text = when {
            originalExt.isEmpty() -> getString(R.string.hint_no_ext)
            lockedExt != null -> getString(R.string.hint_ext_locked)
            else -> getString(R.string.hint_ext_unlocked)
        }
    }

    // ---------------------------------------------------------------- UI 小工具

    private fun setInput(text: String) {
        binding.nameInput.setText(text)
        binding.nameInput.setSelection(binding.nameInput.text?.length ?: 0)
    }

    private fun setStatus(text: String, isError: Boolean) {
        binding.statusText.text = text
        val color = if (isError) {
            ContextCompat.getColor(this, R.color.status_error)
        } else {
            MaterialColors.getColor(
                binding.statusText,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        }
        binding.statusText.setTextColor(color)
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private fun showEmptyState() {
        copyJob?.cancel()
        copyJob = null
        sourceUri = null
        sourceMime = null
        originalName = ""
        originalBase = ""
        originalExt = ""
        currentFile = null
        sessionDir = null
        copyDone = false
        handedOutPaths.clear()
        lockedExt = ""

        binding.oldNameText.text = getString(R.string.old_name_none)
        binding.nameInputLayout.suffixText = null
        setInput("")
        binding.allowExtCheck.setOnCheckedChangeListener(null)
        binding.allowExtCheck.isChecked = false
        binding.allowExtCheck.isEnabled = false
        binding.allowExtCheck.setOnCheckedChangeListener { _, isChecked -> applyExtLock(!isChecked) }
        binding.pickButton.visibility = View.VISIBLE
        binding.sendButton.isEnabled = false
        binding.progress.visibility = View.GONE
        binding.hintText.text = getString(R.string.hint_no_ext)
        setStatus(getString(R.string.status_no_file), isError = false)
    }

    /** 清理 1 小时前的缓存会话，不动本次分享出来的文件 */
    private fun cleanupOldSessions() {
        try {
            val root = File(cacheDir, SHARED_DIR)
            val cutoff = System.currentTimeMillis() - SESSION_TTL_MS
            root.listFiles()?.forEach { child ->
                if (child.lastModified() < cutoff) child.deleteRecursively()
            }
        } catch (_: Throwable) {
            // 清理失败无关紧要
        }
    }

    private companion object {
        const val SHARED_DIR = "shared"
        const val INCOMING_NAME = "_incoming"
        const val FALLBACK_MIME = "application/octet-stream"
        const val CHUNK_SIZE = 1048576L          // 1 MiB
        const val COPY_BUFFER = 65536
        const val MAX_STALLS = 3
        const val SESSION_TTL_MS = 3600000L      // 1 小时
        const val PROGRESS_BYTE_STEP = 4194304L  // 4 MiB
    }
}
