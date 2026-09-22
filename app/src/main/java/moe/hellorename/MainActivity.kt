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
 * 分享接收 → 改名 → 再分享。
 *
 * 核心流程：
 *  1. 从别的应用分享进来时拿到一个 content:// URI（临时读授权，且授权不可转发）。
 *  2. 立刻把源文件复制到 cacheDir/shared/<session>/_incoming<ext>，之后原应用关掉也不影响。
 *  3. 用户在界面上改基础名；扩展名默认锁定（用 TextInputLayout 的 suffix 展示，不可编辑）。
 *  4. 点发送时只需把缓存里的文件 rename 成新文件名（同一目录内瞬时完成，不产生第二份数据），
 *     再通过自己的 FileProvider 把 URI 交给系统分享面板。
 *
 * 全应用不需要任何权限，也不用悬浮窗 / 前台服务。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var sourceUri: Uri? = null
    private var sourceMime: String? = null
    private var originalName: String = ""
    private var originalBase: String = ""
    private var originalExt: String = ""   // 含前导 '.'，没有扩展名时为空串

    private var sessionDir: File? = null
    private var currentFile: File? = null  // 缓存里代表当前文件名的那个文件
    private var copyJob: Job? = null
    private var copyDone = false

    /** null = 扩展名可编辑；非 null = 扩展名锁定为该值（可能是空串） */
    private var lockedExt: String? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 从 SAF 拿到的授权可以持久化，顺手记下来（失败也无所谓）
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Throwable) {
                // 普通分享过来的 URI 不允许持久化，忽略
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

        sourceUri = uri
        sourceMime = try {
            contentResolver.getType(uri)
        } catch (_: Throwable) {
            null
        }

        originalName = queryDisplayName(uri)
        val dot = originalName.lastIndexOf('.')
        if (dot > 0) {
            originalBase = originalName.substring(0, dot)
            originalExt = originalName.substring(dot)
        } else {
            originalBase = originalName.ifBlank { getString(R.string.fallback_name) }
            originalExt = ""
        }

        binding.oldNameText.text = originalName.ifBlank { getString(R.string.fallback_name) }
        binding.pickButton.visibility = View.GONE

        // 默认：扩展名锁定
        lockedExt = originalExt
        binding.allowExtCheck.setOnCheckedChangeListener(null)
        binding.allowExtCheck.isChecked = false
        binding.allowExtCheck.isEnabled = originalExt.isNotEmpty()
        binding.allowExtCheck.setOnCheckedChangeListener { _, isChecked -> applyExtLock(!isChecked) }
        binding.nameInputLayout.suffixText = originalExt.ifEmpty { null }
        setInput(originalBase)
        updateHint()

        if (extraCount > 1) {
            setStatus(getString(R.string.status_multi, extraCount), isError = false)
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
            // 忽略，走下面的兜底
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

        binding.progress.visibility = View.VISIBLE
        binding.sendButton.isEnabled = false

        copyJob = lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { copyUriToFile(uri, target) }
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

    /**
     * 优先用 file descriptor + FileChannel.transferTo（零拷贝、大文件快），
     * 失败（有些 Provider 给的是 pipe）再退回普通的流复制。
     */
    private fun copyUriToFile(uri: Uri, target: File): Boolean {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    FileOutputStream(target).use { output ->
                        val inChannel = input.channel
                        val outChannel = output.channel
                        var position = 0L
                        var total = 0L
                        while (true) {
                            val moved = inChannel.transferTo(position, CHUNK_SIZE, outChannel)
                            if (moved <= 0L) break
                            position += moved
                            total += moved
                        }
                        if (total > 0L) return true
                    }
                }
            }
        } catch (_: Throwable) {
            // 走下面的兜底
        }
        target.delete()

        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
                true
            } ?: false
        } catch (_: Throwable) {
            target.delete()
            false
        }
    }

    // ---------------------------------------------------------------- 改名 + 分享

    private fun onSendClicked() {
        val uri = sourceUri ?: run {
            setStatus(getString(R.string.status_no_file), isError = true)
            return
        }
        val file = currentFile
        if (!copyDone || file == null || !file.exists()) {
            startCopy(uri)
            setStatus(getString(R.string.status_copying), isError = false)
            return
        }

        val (rawBase, rawExt) = currentNameParts()
        val finalName = buildFinalName(rawBase, rawExt)
        val target = File(sessionDir ?: file.parentFile, finalName)

        if (target.absolutePath != file.absolutePath) {
            if (target.exists()) target.delete()
            if (!file.renameTo(target)) {
                setStatus(getString(R.string.status_rename_failed), isError = true)
                return
            }
        }
        currentFile = target

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
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            name.substring(0, dot) to name.substring(dot)
        } else {
            name to ""
        }
    }

    private fun buildFinalName(rawBase: String, rawExt: String): String {
        val ext = sanitizeExt(rawExt)
        val base = sanitizeBase(rawBase).ifEmpty { sanitizeBase(originalBase) }.ifEmpty { "file" }
        val maxBase = (MAX_NAME_LENGTH - ext.length).coerceAtLeast(MIN_BASE_LENGTH)
        return base.take(maxBase) + ext
    }

    private fun sanitizeBase(raw: String): String =
        raw.replace(ILLEGAL_CHARS, "_").trim().trimEnd('.', ' ').trim()

    private fun sanitizeExt(raw: String): String {
        val cleaned = raw.trim().replace(ILLEGAL_CHARS, "").removePrefix(".")
        if (cleaned.isEmpty()) return ""
        return "." + cleaned.take(MAX_EXT_LENGTH)
    }

    private fun resolveMime(ext: String): String {
        val clean = ext.removePrefix(".").lowercase(Locale.US)
        val fromExt = if (clean.isEmpty()) {
            null
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(clean)
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
        if (lock) {
            val base = if (previous != null) raw else stripExt(raw)
            lockedExt = originalExt
            binding.nameInputLayout.suffixText = originalExt.ifEmpty { null }
            setInput(base)
        } else {
            val base = if (previous != null) raw else stripExt(raw)
            val ext = previous ?: originalExt
            lockedExt = null
            binding.nameInputLayout.suffixText = null
            setInput(base + ext)
        }
        updateHint()
    }

    private fun stripExt(text: String): String {
        val dot = text.lastIndexOf('.')
        return if (dot > 0) text.substring(0, dot) else text
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

    /** 清理 1 小时前的缓存会话，不会动到本次分享出来的文件 */
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
        const val MAX_EXT_LENGTH = 12
        const val MAX_NAME_LENGTH = 150
        const val MIN_BASE_LENGTH = 20
        const val CHUNK_SIZE = 1048576L          // 1 MiB
        const val COPY_BUFFER = 65536
        const val SESSION_TTL_MS = 3600000L      // 1 小时

        val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\r\n\t]")
    }
}
