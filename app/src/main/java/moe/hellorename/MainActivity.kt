package moe.hellorename

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
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
 * 分享接收 → 改名 → 再分享 / 保存到下载。全应用零权限、纯前台 Activity。
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

    /** 复制会话号：每次开始新复制自增；旧会话的迟到进度回调一律丢弃（修“卡在 100%”的根源） */
    private var copySession = 0

    /** 已给出最终结果（完成/失败）的会话号；同一会话之后到达的进度回调同样丢弃 */
    private var finishedSession = -1

    /** SAF 选择器打开期间暂存“待保存”的缓存文件 */
    private var savePendingFile: File? = null

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

    /** Android 7–9 保存到下载走 SAF：用户选好位置后把缓存文件流式写过去 */
    private val pickSaveLocation =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val source = savePendingFile
            savePendingFile = null
            val dest = result.data?.data
            if (source == null || result.resultCode != Activity.RESULT_OK || dest == null) {
                // 用户取消或没拿到目标 URI：恢复按钮即可，缓存文件原封不动
                binding.saveButton.isEnabled = copyDone
                return@registerForActivityResult
            }
            binding.saveButton.isEnabled = false
            setStatus(getString(R.string.status_saving), isError = false)
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { writeToUri(source, dest) }
                if (ok) {
                    finishWithToast(getString(R.string.toast_saved, source.name))
                } else {
                    setStatus(getString(R.string.status_save_failed), isError = true)
                    Toast.makeText(
                        applicationContext,
                        R.string.toast_save_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    binding.saveButton.isEnabled = copyDone
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.sendButton.setOnClickListener { onSendClicked() }
        binding.saveButton.setOnClickListener { onSaveClicked() }
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
        // 先取消上一次复制并换会话号：被取消协程里的 IO 循环可能还会吐出几个进度回调，
        // 它们只认旧会话号，永远覆盖不掉新会话的最终状态（“卡在 100%”的根源）
        copyJob?.cancel()
        val session = ++copySession

        val dir = File(File(cacheDir, SHARED_DIR), System.currentTimeMillis().toString())
        if (!dir.exists() && !dir.mkdirs()) {
            copyJob = null
            copyDone = false
            binding.sendButton.isEnabled = false
            binding.saveButton.isEnabled = false
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
        binding.saveButton.isEnabled = false

        copyJob = lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                copyUriToFile(uri, target, { !isActive }) { copied, total ->
                    onCopyProgress(session, copied, total)
                }
            }
            // 会话已换代或本协程被取消：什么都不许碰，避免把新状态覆盖掉
            if (!isActive || session != copySession) return@launch
            finishedSession = session
            binding.progress.visibility = View.GONE
            copyDone = ok
            binding.sendButton.isEnabled = ok
            binding.saveButton.isEnabled = ok
            if (ok) {
                setStatus(getString(R.string.status_ready), isError = false)
            } else {
                currentFile = null
                setStatus(getString(R.string.status_copy_failed), isError = true)
            }
        }
    }

    /**
     * 复制进度回调，来自 IO 线程，已做节流。
     * [session] 是发起本次复制时的会话号：旧会话的迟到回调、以及本会话已经出过
     * 最终结果之后的回调，一律丢弃——这正是“文案卡在 100% 不显示复制完成”的根源。
     */
    private fun onCopyProgress(session: Int, copied: Long, total: Long) {
        if (total <= 0L) {
            if (copied - lastReportedBytes < PROGRESS_BYTE_STEP) return
            lastReportedBytes = copied
            val text = getString(R.string.status_copying_size, formatSize(copied))
            runOnUiThread {
                if (isStale(session)) return@runOnUiThread
                setStatus(text, isError = false)
            }
            return
        }
        val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
        if (percent == lastPercent) return
        lastPercent = percent
        runOnUiThread {
            if (isStale(session)) return@runOnUiThread
            binding.progress.isIndeterminate = false
            binding.progress.progress = percent
            setStatus(getString(R.string.status_copying_percent, percent), isError = false)
        }
    }

    /** 旧会话（已被取代/取消）或已经出过最终结果的会话：迟到的进度更新一律丢弃 */
    private fun isStale(session: Int): Boolean =
        session != copySession || session == finishedSession

    /**
     * 复制源 URI 到本地文件。
     *
     * 快路径用 file descriptor + FileChannel.transferTo；因为 transferTo 允许“少传”甚至返回 0，
     * 所以必须用 statSize 校验字节数，对不上就整份丢弃、改走流复制，避免静默截断。
     *
     * [cancelled] 返回 true 时立即中止（协程被取消后的协作式出口），半成品文件直接丢弃。
     */
    private fun copyUriToFile(
        uri: Uri,
        target: File,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
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
                        while (!cancelled()) {
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
                        if (cancelled()) {
                            target.delete()
                            return false
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
            val ok = contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var total = 0L
                    while (!cancelled()) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onProgress(total, -1L)
                    }
                }
                !cancelled()
            } ?: false
            if (!ok) target.delete()
            ok
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
        val file = prepareFinalFile() ?: return
        val finalName = file.name

        val shareUri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (_: Throwable) {
            setStatus(getString(R.string.status_rename_failed), isError = true)
            return
        }

        val mime = resolveMime(FileNameUtils.split(finalName).ext)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_TITLE, finalName)
            clipData = ClipData.newUri(contentResolver, finalName, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(sendIntent, getString(R.string.chooser_title)))
            // 成功交出去了：Toast 提醒“选别的应用”，然后关掉主界面，防止用户回头又选本应用
            finishWithToast(getString(R.string.toast_sent))
        } catch (_ : ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_share_app, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 把输入框里的改名落到缓存文件上（同目录 rename；已交付过的旧名改成复制）。
     * 返回最终要交付的文件；任何一步失败都设置状态并返回 null。
     * 「发送」和「保存到下载」共用它，保证两个按钮操作的是同一个名字、同一条交付记录。
     */
    private fun prepareFinalFile(): File? {
        val uri = sourceUri ?: run {
            setStatus(getString(R.string.status_no_file), isError = true)
            return null
        }
        val file = currentFile
        if (!copyDone || file == null || !file.exists()) {
            setStatus(getString(R.string.status_copying), isError = false)
            startCopy(uri)
            return null
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
                return null
            }
        }
        currentFile = target
        handedOutPaths.add(target.absolutePath)
        return target
    }

    /** 当前输入对应的 (基础名, 扩展名)；扩展名锁定时输入框里只有基础名，多余的后缀一律不采信 */
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

    // ---------------------------------------------------------------- 保存到下载

    private fun onSaveClicked() {
        val file = prepareFinalFile() ?: return
        val finalName = file.name
        val mime = resolveMime(FileNameUtils.split(finalName).ext)
        binding.saveButton.isEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(file, finalName, mime)
        } else {
            saveViaSaf(file, finalName, mime)
        }
    }

    /**
     * Android 10+：MediaStore 直写公共下载目录，零权限、无弹窗。
     * IS_PENDING 期间条目对其他应用不可见，写完再转正；重名由系统自动编号，不会覆盖已有文件。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(source: File, displayName: String, mime: String) {
        setStatus(getString(R.string.status_saving), isError = false)
        binding.progress.isIndeterminate = true
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                insertIntoDownloads(source, displayName, mime)
            }
            binding.progress.visibility = View.GONE
            if (saved != null) {
                finishWithToast(getString(R.string.toast_saved, displayName))
            } else {
                setStatus(getString(R.string.status_save_failed), isError = true)
                Toast.makeText(
                    applicationContext,
                    R.string.toast_save_failed,
                    Toast.LENGTH_LONG,
                ).show()
                binding.saveButton.isEnabled = copyDone
            }
        }
    }

    /** 只在 IO 线程调用；失败返回 null 并顺手清掉半成品条目 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertIntoDownloads(source: File, displayName: String, mime: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val out = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val stream = resolver.openOutputStream(out) ?: error("openOutputStream returned null")
            stream.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            }
            resolver.update(
                out,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            out
        } catch (_: Throwable) {
            try {
                resolver.delete(out, null, null)
            } catch (_: Throwable) {
                // 清理失败就留给系统回收
            }
            null
        }
    }

    /**
     * Android 7–9：SAF 选择器（这个区间零权限的唯一稳妥做法；直接写文件要
     * WRITE_EXTERNAL_STORAGE，会破坏本项目的零权限承诺，也会挂掉 QA 体检的权限门禁）。
     */
    private fun saveViaSaf(source: File, displayName: String, mime: String) {
        savePendingFile = source
        setStatus(getString(R.string.status_saving), isError = false)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, displayName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 尽力把选择器定位到下载目录；定位失败系统自行回退，无副作用
                putExtra(MediaStore.EXTRA_INITIAL_URI, downloadsDocumentUri())
            }
        }
        try {
            pickSaveLocation.launch(intent)
        } catch (_: Throwable) {
            savePendingFile = null
            binding.saveButton.isEnabled = copyDone
            setStatus(getString(R.string.status_save_failed), isError = true)
            Toast.makeText(applicationContext, R.string.toast_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** DocumentsUI 里主存储的下载目录，纯提示性质（EXTRA_INITIAL_URI 是 API 26+） */
    private fun downloadsDocumentUri(): Uri =
        Uri.parse(
            "content://com.android.externalstorage.documents/document/" +
                Uri.encode("primary:" + Environment.DIRECTORY_DOWNLOADS),
        )

    /** 把缓存文件流式写到 SAF 返回的 URI（"wt" 保证覆盖写，不残留旧内容） */
    private fun writeToUri(source: File, dest: Uri): Boolean = try {
        contentResolver.openOutputStream(dest, "wt")?.use { output ->
            FileInputStream(source).use { input -> input.copyTo(output) }
        } != null
    } catch (_: Throwable) {
        false
    }

    // ---------------------------------------------------------------- 扩展名锁

    private fun applyExtLock(lock: Boolean) {
        val raw = binding.nameInput.text?.toString().orEmpty()
        val previous = lockedExt
        val base = if (previous != null) raw else FileNameUtils.stripExt(raw)
        if (lock) {
            // 锁定态：输入框里只留基础名，扩展名既不进文本、也不再用 suffix 展示
            lockedExt = originalExt
            setInput(base)
        } else {
            val ext = previous ?: originalExt
            lockedExt = null
            setInput(base + ext)
        }
        updateHint()
    }

    private fun updateHint() {
        // 锁定时扩展名不显示在输入框里，改由提示行说明保留的是哪个扩展名
        val locked = lockedExt
        binding.hintText.text = when {
            originalExt.isEmpty() -> getString(R.string.hint_no_ext)
            locked != null -> getString(R.string.hint_ext_locked, locked)
            else -> getString(R.string.hint_ext_unlocked)
        }
    }

    // ---------------------------------------------------------------- UI 小工具

    /** 成功交付：Toast 提示后关掉主界面（Toast 挂在应用上下文上，Activity 销毁照样显示） */
    private fun finishWithToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }

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
        setInput("")
        binding.allowExtCheck.setOnCheckedChangeListener(null)
        binding.allowExtCheck.isChecked = false
        binding.allowExtCheck.isEnabled = false
        binding.allowExtCheck.setOnCheckedChangeListener { _, isChecked -> applyExtLock(!isChecked) }
        binding.pickButton.visibility = View.VISIBLE
        binding.sendButton.isEnabled = false
        binding.saveButton.isEnabled = false
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
