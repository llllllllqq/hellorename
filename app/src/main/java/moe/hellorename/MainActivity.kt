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
import android.provider.DocumentsContract
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

    /** 改名（含重复复制）正在进行：期间禁用按钮，并忽略再次触发 */
    private var preparingFile = false

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
                // 用户取消或没拿到目标 URI：恢复按钮与状态文案，缓存文件原封不动
                setButtonsEnabled(copyDone)
                setStatus(getString(R.string.status_save_canceled), isError = false)
                return@registerForActivityResult
            }
            binding.saveButton.isEnabled = false
            setStatus(getString(R.string.status_saving), isError = false)
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { writeToUri(source, dest) }
                if (ok) {
                    // 真的写进去了才算交付过，之后这条路径不许再被 rename/删除
                    handedOutPaths.add(source.absolutePath)
                    finishWithToast(
                        getString(R.string.toast_saved, ellipsizeForToast(source.name)),
                    )
                } else {
                    setStatus(getString(R.string.status_save_failed), isError = true)
                    Toast.makeText(
                        applicationContext,
                        R.string.toast_save_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    setButtonsEnabled(copyDone)
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
            // 按钮保持可点：再点一次会换一个新的会话目录重试，别把界面变成死路
            setButtonsEnabled(true)
            setStatus(getString(R.string.status_copy_failed), isError = true)
            return
        }
        // 临时文件名只借用规范化后的扩展名：originalExt 直接来自对方应用的 DISPLAY_NAME，
        // 可能超长或含 `/ \ :` 等字符，不能原样拼进路径
        val incomingExt = FileNameUtils.sanitizeExt(originalExt)
        val target = File(dir, INCOMING_NAME + incomingExt)
        sessionDir = dir
        currentFile = target
        copyDone = false
        lastPercent = -1
        lastReportedBytes = 0L

        binding.progress.isIndeterminate = true
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        setButtonsEnabled(false)

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
            // 成功可以交付；失败也保持可点（再点“发送/保存”会重新复制），两种情况都不留死路
            setButtonsEnabled(true)
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
     * 快路径用 file descriptor + FileChannel.transferTo；但 transferTo 允许“少传”甚至返回 0，
     * 所以**只有能拿到 statSize 时才走它**，并且必须用 statSize 校验字节数：
     *  - 拿不到 statSize（管道型 Provider，`statSize` 返回 -1）→ 快路径无法校验，直接走流复制；
     *  - 校验不符（少传/静默失败）→ 丢掉半成品，同样退回 64 KiB 流复制。
     * 两条兜底路径都不会把不完整的文件当成成功。
     *
     * [cancelled] 返回 true 时立即中止（协程被取消后的协作式出口），半成品文件直接丢弃。
     */
    private fun copyUriToFile(
        uri: Uri,
        target: File,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        var verified = false
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val statSize = try {
                    pfd.statSize
                } catch (_: Throwable) {
                    -1L
                }
                if (statSize >= 0L) {
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
                            // 字节数对不上就不算成功（含被取消的情况）
                            verified = !cancelled() && total == statSize
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            verified = false
        }
        if (verified) return true

        // 半成品一律丢掉，改用下面的流复制重来一遍
        target.delete()
        if (cancelled()) return false
        return streamCopyToFile(uri, target, cancelled, onProgress)
    }

    /** 兜底复制：64 KiB 缓冲，读到 EOF（-1）才算完整；取消则丢弃半成品。 */
    private fun streamCopyToFile(
        uri: Uri,
        target: File,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Boolean = try {
        val complete = contentResolver.openInputStream(uri)?.use { input ->
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
        if (!complete) target.delete()
        complete
    } catch (_: Throwable) {
        target.delete()
        false
    }

    /**
     * 同目录复制（只在“已经交付过的文件”需要改名时使用，避免打破对方正在读的 URI）。
     * 同样做字节数校验：transferTo 少传时判失败并删掉半成品，不留静默截断的文件。
     */
    private fun copyFile(source: File, target: File): Boolean {
        val expected = source.length()
        val copied = try {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    var position = 0L
                    var total = 0L
                    while (true) {
                        val moved = input.channel.transferTo(position, CHUNK_SIZE, output.channel)
                        if (moved <= 0L) break
                        position += moved
                        total += moved
                    }
                    total
                }
            }
        } catch (_: Throwable) {
            -1L
        }
        if (copied != expected) {
            target.delete()
            return false
        }
        return true
    }

    // ---------------------------------------------------------------- 改名 + 分享

    /** 改名结果：只有 [Ready] 才允许继续交付 */
    private sealed interface PrepareResult {
        /** 缓存文件已经是 [file] 这个名字，可以交付 */
        data class Ready(val file: File) : PrepareResult

        /** 缓存文件不在了（复制未完成/被清理），需要重新复制 */
        data object NeedCopy : PrepareResult

        /** 改名失败（重名或名字过长/非法），文件保持原样 */
        data object RenameFailed : PrepareResult
    }

    /**
     * 主线程取出的“改名快照”：IO 线程只读这一份不可变数据，不回读 Activity 的可变字段，
     * 避免改名期间又来了新分享时读到一半新一半旧的状态。
     */
    private class RenameSnapshot(
        val raw: String,
        val lockedExt: String?,
        val fallbackBase: String,
        val fallbackExt: String,
        val file: File,
        val sessionDir: File?,
        val handedOut: Set<String>,
    )

    private fun onSendClicked() {
        prepareThenAct { file -> startShare(file) }
    }

    /** 把改名后的文件交给系统分享面板；只有 startActivity 成功才算真正交付 */
    private fun startShare(file: File) {
        val finalName = file.name
        val shareUri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (_: Throwable) {
            setStatus(getString(R.string.status_rename_failed), isError = true)
            setButtonsEnabled(copyDone)
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
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_share_app, Toast.LENGTH_LONG).show()
            setButtonsEnabled(copyDone)
            return
        }
        // 真的交出去了：登记这条路径“别人可能在读”，之后不许再 rename/删除它
        handedOutPaths.add(file.absolutePath)
        // Toast 提醒“选别的应用”，然后关掉主界面，防止用户回头又选本应用
        finishWithToast(getString(R.string.toast_sent, getString(R.string.app_name)))
    }

    /**
     * 「发送」和「保存到下载」的公共前置：先把改名落到缓存文件上，再执行 [onReady]。
     *
     * 改名里的重复复制（大文件）和 rename 全部丢到 IO 线程，不再卡主线程；
     * 期间禁用两个按钮防连点。是否算“交付过”由 [onReady] 决定：只有真正交付成功
     * 才会把路径登记进 [handedOutPaths]，取消/失败不会。
     */
    private fun prepareThenAct(onReady: (File) -> Unit) {
        val uri = sourceUri
        if (uri == null) {
            setStatus(getString(R.string.status_no_file), isError = true)
            return
        }
        val file = currentFile
        if (!copyDone || file == null || !file.exists()) {
            // 复制没成功或被清掉了：重新复制一次（复制失败后按钮保持可点，就是走这条路重试）
            setStatus(getString(R.string.status_copying), isError = false)
            startCopy(uri)
            return
        }
        if (preparingFile) return
        preparingFile = true

        // 输入框只能在主线程读，其余需要的状态一并快照，IO 线程不再碰 View/字段
        val snapshot = RenameSnapshot(
            raw = binding.nameInput.text?.toString().orEmpty(),
            lockedExt = lockedExt,
            fallbackBase = originalBase,
            fallbackExt = originalExt,
            file = file,
            sessionDir = sessionDir,
            handedOut = handedOutPaths.toSet(),
        )
        val session = copySession
        setButtonsEnabled(false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { prepareFinalFile(snapshot) }
            preparingFile = false
            // 改名期间又分享了新文件：这次结果作废，界面状态交给新会话
            if (session != copySession) return@launch
            when (result) {
                is PrepareResult.Ready -> {
                    currentFile = result.file
                    onReady(result.file)
                }
                PrepareResult.NeedCopy -> {
                    setStatus(getString(R.string.status_copying), isError = false)
                    startCopy(uri)
                }
                PrepareResult.RenameFailed -> {
                    setStatus(getString(R.string.status_rename_failed), isError = true)
                    setButtonsEnabled(copyDone)
                }
            }
        }
    }

    /**
     * 把输入框里的改名落到缓存文件上（同目录 rename；已交付过的旧名改成复制）。
     * 只在 IO 线程调用，不碰任何 View，结果由调用方 [prepareThenAct] 落到界面上。
     */
    private fun prepareFinalFile(snapshot: RenameSnapshot): PrepareResult {
        val file = snapshot.file
        if (!file.exists()) return PrepareResult.NeedCopy

        val (rawBase, rawExt) = namePartsFor(snapshot)
        val finalName = FileNameUtils.buildFinalName(rawBase, rawExt, snapshot.fallbackBase)
        val target = File(snapshot.sessionDir ?: file.parentFile, finalName)

        if (target.absolutePath != file.absolutePath) {
            val succeeded = if (snapshot.handedOut.contains(file.absolutePath)) {
                // 旧名字已经交给别人了，动它会让对方正在读的 URI 失效，所以另复制一份
                copyFile(file, target)
            } else {
                if (target.exists()) target.delete()
                file.renameTo(target)
            }
            if (!succeeded) return PrepareResult.RenameFailed
        }
        return PrepareResult.Ready(target)
    }

    /** 快照对应的 (基础名, 扩展名)；扩展名锁定时输入框里只有基础名，多余的后缀一律不采信 */
    private fun namePartsFor(snapshot: RenameSnapshot): Pair<String, String> {
        val locked = snapshot.lockedExt
        if (locked != null) {
            val base = snapshot.raw.trim()
            return (base.ifEmpty { snapshot.fallbackBase }) to locked
        }
        val name = snapshot.raw.trim()
        if (name.isEmpty()) return snapshot.fallbackBase to snapshot.fallbackExt
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
        prepareThenAct { file ->
            val finalName = file.name
            val mime = resolveMime(FileNameUtils.split(finalName).ext)
            binding.saveButton.isEnabled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(file, finalName, mime)
            } else {
                saveViaSaf(file, finalName, mime)
            }
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
                // 真的写成功才算交付过
                handedOutPaths.add(source.absolutePath)
                finishWithToast(getString(R.string.toast_saved, ellipsizeForToast(displayName)))
            } else {
                setStatus(getString(R.string.status_save_failed), isError = true)
                Toast.makeText(
                    applicationContext,
                    R.string.toast_save_failed,
                    Toast.LENGTH_LONG,
                ).show()
                setButtonsEnabled(copyDone)
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
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsDocumentUri())
            }
        }
        try {
            pickSaveLocation.launch(intent)
        } catch (_: Throwable) {
            savePendingFile = null
            setButtonsEnabled(copyDone)
            setStatus(getString(R.string.status_save_failed), isError = true)
            Toast.makeText(applicationContext, R.string.toast_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * DocumentsUI 里主存储的下载目录，纯提示性质（EXTRA_INITIAL_URI 是 API 26+）。
     * 用官方 API 拼而不是手写字符串：documentId 里的 `:` 由系统自己做 URI 编码。
     */
    private fun downloadsDocumentUri(): Uri = DocumentsContract.buildDocumentUri(
        EXTERNAL_STORAGE_AUTHORITY,
        "primary:${Environment.DIRECTORY_DOWNLOADS}",
    )

    /**
     * 把缓存文件流式写到 SAF 返回的 URI。
     * 先试 "wt"（截断写，不残留旧内容）；个别第三方 DocumentsProvider 不支持该模式，
     * 会直接抛异常，此时退回 "w"（同样会截断后重写，不会把两份内容拼在一起）。
     */
    private fun writeToUri(source: File, dest: Uri): Boolean =
        writeToUriWithMode(source, dest, "wt") || writeToUriWithMode(source, dest, "w")

    private fun writeToUriWithMode(source: File, dest: Uri, mode: String): Boolean = try {
        contentResolver.openOutputStream(dest, mode)?.use { output ->
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

    /** 「发送」与「保存到下载」共用的按钮开关（复制/改名期间整体禁用，防连点） */
    private fun setButtonsEnabled(enabled: Boolean) {
        binding.sendButton.isEnabled = enabled
        binding.saveButton.isEnabled = enabled
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    /**
     * Android 12+ 的 Toast 最多显示两行，超出的部分会被系统直接截掉，
     * 所以放进 Toast 的文件名先掐短：保留首尾（尾部能保住扩展名），中间省略。
     */
    private fun ellipsizeForToast(name: String): String {
        if (name.length <= TOAST_NAME_MAX_CHARS) return name
        val head = (TOAST_NAME_MAX_CHARS - 1) / 2
        val tail = TOAST_NAME_MAX_CHARS - 1 - head
        return name.take(head) + "…" + name.takeLast(tail)
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
        const val TOAST_NAME_MAX_CHARS = 32      // Android 12+ Toast 只有两行，文件名先掐短
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
