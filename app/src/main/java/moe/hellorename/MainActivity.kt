package moe.hellorename

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.hellorename.databinding.ActivityMainBinding
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 分享接收 → 改名 → 再分享 / 保存到下载。全应用零权限、纯前台 Activity。
 *
 * 关键约束（决定了实现方式）：
 *  - 别的应用分享进来的 content:// URI 是“临时且不可转发”的授权，我们既不能原地改名，
 *    也不能把授权转给下一个应用，所以必须先把数据复制成自己的。
 *  - 复制只做一次：cacheDir/shared/<会话>/_incoming<ext>；改名时同目录 rename，零字节拷贝。
 *  - 一旦某个路径已经交给系统分享面板，就再也不能 rename/删除它（目标应用可能正在异步读取），
 *    因此再次改名要改成复制，见 [handedOutPaths]；跨进程的那一半保护由 [CacheStore.markDelivered]
 *    落在磁盘上，否则一重启就失效。
 *  - 缓存清理的规则与磁盘操作都在 [CacheCleaner] / [CacheStore]（纯 java.io，可单测），
 *    本类只负责“在正确的线程、正确的时机调用它，并把还活着的会话登记进 [CacheState]”。
 *
 * v1.3.2 起，所有会改动界面的异步操作都带一个 [OperationRegistry] 的 token：
 * 新分享、重置、Activity 重建都会换代，旧任务回到主线程后只允许清理自己的半成品，
 * 不允许再更新 UI、写交付标记或 finish。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 会话副本目录：cacheDir/shared（FileProvider 暴露的就是它） */
    private val sharedRoot: File get() = File(cacheDir, CacheStore.SHARED_DIR)

    /** 已交付标记目录：cacheDir/shared-state，刻意不放在 shared/ 里面，避免被 FileProvider 暴露 */
    private val stateRoot: File get() = File(cacheDir, CacheStore.STATE_DIR)

    private var sourceUri: Uri? = null
    private var sourceMime: String? = null
    private var originalName: String = ""
    private var originalBase: String = ""
    private var originalExt: String = ""   // 含前导 '.'，无扩展名时为空串

    /** 源文件字节数（-1 = 对方报不出来），仅用于复制前的空间预检 */
    private var sourceSize: Long = -1L

    /** 本次分享的处理模式：单文件（改名/发送/保存）、多文件、纯文本/链接 */
    private var mode = ShareMode.NONE

    /** 多文件模式：按分享顺序保存；名称/MIME 在读取元数据后填充 */
    private var multiUris: List<Uri> = emptyList()
    private var multiNames: List<String> = emptyList()
    private var multiMimes: List<String> = emptyList()

    /** 每个待保存文件的相对目录（不含 Download 根，空串表示根目录）；与 multiUris 平行 */
    private var multiPaths: List<String> = emptyList()

    /** 多文件保存已经成功的下标：部分失败后重试只补没成功的 */
    private val multiSaved = mutableSetOf<Int>()
    private var multiSaving = false
    private var multiAwaitingTree = false
    private var textPreparing = false

    /** 纯文本/不支持链接/未知请求的原始内容：最终写成 UTF-8 文本文件 */
    private var textContent: String = ""

    /** 未知分享兜底：内容是对原始 Intent 的转储，而不是普通文本 */
    private var textUnknown = false

    /** 纯文本/未知模式的“发送”要原样转发这个来源 Intent（不重新编码内容） */
    private var originalShareIntent: Intent? = null

    private var sessionDir: File? = null
    private var currentFile: File? = null  // 缓存里代表“当前文件名”的那个文件
    private var copyJob: Job? = null
    private var prepareJob: Job? = null
    private var saveJob: Job? = null
    private var backgroundJob: Job? = null
    private var copyDone = false

    /** 复制完成后对缓存文件回读计算的校验值；null = 尚未算出/已失效 */
    private var checksums: FileChecksums? = null

    /** 已经交给系统分享面板的绝对路径，不允许再被 rename/删除 */
    private val handedOutPaths = mutableSetOf<String>()

    private val lastPercent = AtomicInteger(-1)
    private val lastReportedBytes = AtomicLong(0L)
    private val hashPercent = AtomicInteger(-1)

    /** 哈希阶段已结束的代际：迟到的哈希进度不能再覆盖“完成/失败”状态 */
    @Volatile
    private var hashFinishedGeneration = -1L

    /** 操作代际：新分享/重置/重建都会换代 */
    private val operations = OperationRegistry()

    /** 已经出过最终结果的代际：之后迟到的进度回调一律丢弃 */
    @Volatile
    private var finishedGeneration = -1L

    /** 会话目录号，只用于生成目录名；随机后缀由 [CacheStore.newSessionName] 提供 */
    private var sessionCounter = 0

    /** 元数据查询还在进行：期间不要因为 onStart 自动重试复制 */
    private var loadingMeta = false

    /** SAF 选择器打开期间暂存“待保存”的缓存文件 */
    private var savePendingFile: File? = null
    private var saveGeneration = 0L

    /** 改名（含重复复制）正在进行：期间禁用按钮，并忽略再次触发 */
    private var preparingFile = false

    /** 保存操作进行中（用于重建后提示“上次保存被中断”） */
    private var wasSaving = false

    /** null = 扩展名可编辑；非 null = 扩展名锁定为该值（可能是空串） */
    private var lockedExt: String? = null

    /** 第一次 onStart 不算“从后台回来”，避免和异步元数据查询抢着启动复制 */
    private var startedOnce = false

    /**
     * 与 Activity 生命周期解耦的清理协程：取消操作时要把半成品删掉，
     * 但这时 lifecycleScope 可能已经跟着 Activity 一起被取消了。
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 拿到 MediaStore 插入的 URI 后立刻记下；Activity 在复制中途被销毁时用它兜底删除 */
    @Volatile
    private var pendingMediaUri: Uri? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            loadSource(uri)
        }
    }

    /** Android 7–9 保存到下载走 SAF：用户选好位置后把缓存文件流式写过去 */
    private val pickSaveLocation =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val source = savePendingFile
            savePendingFile = null
            val dest = result.data?.data
            val generation = saveGeneration

            if (source == null) {
                // 旧回调/Activity 重建后状态丢失：目标文档可能已经被 DocumentsUI 创建，
                // 能删就删，绝不能再写一个没人知道的半成品。
                if (dest != null) cleanupScope.launch { cleanupDocument(dest) }
                if (operations.isCurrent(generation)) {
                    wasSaving = false
                    setButtonsEnabled(copyDone)
                    setStatus(getString(R.string.status_save_canceled), isError = false)
                }
                return@registerForActivityResult
            }
            if (result.resultCode != Activity.RESULT_OK || dest == null) {
                if (operations.isCurrent(generation)) {
                    wasSaving = false
                    setButtonsEnabled(copyDone)
                    setStatus(getString(R.string.status_save_canceled), isError = false)
                }
                return@registerForActivityResult
            }
            if (!operations.isCurrent(generation) || !source.isFile) {
                // 这次保存已经被新分享/重置取代，目标 URI 是刚创建的，清理掉。
                cleanupScope.launch { cleanupDocument(dest) }
                return@registerForActivityResult
            }

            binding.saveButton.isEnabled = false
            binding.progress.isIndeterminate = true
            binding.progress.progress = 0
            binding.progress.visibility = View.VISIBLE
            setStatus(getString(R.string.status_saving), isError = false)
            wasSaving = true
            saveJob = lifecycleScope.launch {
                val write = try {
                    withContext(Dispatchers.IO) {
                        writeToUri(source, dest) { !operations.isCurrent(generation) }
                    }
                } catch (cancel: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) { cleanupDocument(dest) }
                    throw cancel
                } catch (_: Throwable) {
                    withContext(NonCancellable + Dispatchers.IO) { cleanupDocument(dest) }
                    WriteResult(ok = false, bytes = 0L, incomplete = true)
                }
                binding.progress.visibility = View.GONE
                if (!operations.isCurrent(generation)) {
                    withContext(NonCancellable + Dispatchers.IO) { cleanupDocument(dest) }
                    return@launch
                }
                wasSaving = false
                if (write.ok) {
                    withContext(Dispatchers.IO) { markDelivered(source) }
                    handedOutPaths.add(source.absolutePath)
                    finishWithToast(
                        getString(R.string.toast_saved, FileNameUtils.ellipsizeForToast(source.name)),
                    )
                } else {
                    setButtonsEnabled(copyDone)
                    setStatus(
                        getString(
                            if (write.incomplete) {
                                R.string.status_save_incomplete
                            } else {
                                R.string.status_save_failed
                            },
                        ),
                        isError = true,
                    )
                    Toast.makeText(
                        applicationContext,
                        R.string.toast_save_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

    /**
     * Android 7–9 多文件保存：CREATE_DOCUMENT 一次只能建一个文档，改用目录选择器，
     * 由应用在选中的目录里为每个文件调用 createDocument。
     */
    private val pickMultiTree =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val generation = saveGeneration
            multiAwaitingTree = false
            if (result.resultCode != Activity.RESULT_OK || result.data?.data == null) {
                if (operations.isCurrent(generation) && mode == ShareMode.MULTI) {
                    wasSaving = false
                    setButtonsEnabled(copyDone)
                    setStatus(getString(R.string.status_multi_canceled), isError = false)
                }
                return@registerForActivityResult
            }
            val tree = result.data!!.data!!
            if (!operations.isCurrent(generation) || mode != ShareMode.MULTI || multiUris.isEmpty()) {
                return@registerForActivityResult
            }
            try {
                contentResolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Throwable) {
                // 只是没跨进程持久化，当前这次写入仍然有临时授权
            }
            startMultiTreeSave(tree, generation)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdge()

        binding.sendButton.setOnClickListener { onSendClicked() }
        binding.saveButton.setOnClickListener { onSaveClicked() }
        binding.pickButton.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        binding.allowExtCheck.setOnCheckedChangeListener { _, isChecked -> applyExtLock(!isChecked) }
        binding.copyChecksumsButton.setOnClickListener { copyChecksumsToClipboard() }

        if (savedInstanceState != null) {
            // 配置变更/进程重建：优先恢复，不再无条件重放原始 Intent。
            restoreSession(savedInstanceState)
        } else {
            handleShareIntent(intent)
        }

        // 冷启动强制清一次：删除是真实磁盘操作，必须离开主线程（旧实现在这里同步删，攒几个大文件就卡启动）。
        // 放在恢复之后：恢复出来的会话要先登记成 live，清理才不会把它当成旧会话。
        startCleanup(force = true)
    }

    /**
     * Android 15+ / targetSdk 36 强制 edge-to-edge，不能再靠 windowOptOutEdgeToEdgeEnforcement。
     * 根布局用系统栏/IME inset 做 padding：Toolbar 自身仍保持 56dp，状态栏区域只由根布局让出来。
     * API 34 及以下保持旧的状态栏颜色 + fitsSystemWindows 行为，避免改变老系统外观。
     */
    private fun applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                maxOf(bars.bottom, ime.bottom),
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop 复用同一个界面实例时不会再走 onCreate，这里补一次触发（按 10 分钟节流）
        startCleanup(force = false)
        handleShareIntent(intent)
    }

    @Suppress("DEPRECATION")
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HAS_SOURCE, hasLoadedState())
        outState.putString(KEY_MODE, mode.name)
        outState.putString(KEY_SOURCE_URI, sourceUri?.toString())
        outState.putString(KEY_SOURCE_MIME, sourceMime)
        outState.putString(KEY_ORIGINAL_NAME, originalName)
        outState.putString(KEY_ORIGINAL_BASE, originalBase)
        outState.putString(KEY_ORIGINAL_EXT, originalExt)
        outState.putLong(KEY_SOURCE_SIZE, sourceSize)
        outState.putString(KEY_SESSION_DIR, sessionDir?.absolutePath)
        outState.putString(KEY_CURRENT_FILE, currentFile?.absolutePath)
        outState.putBoolean(KEY_COPY_DONE, copyDone)
        outState.putString(KEY_MD5, checksums?.md5)
        outState.putString(KEY_SHA1, checksums?.sha1)
        outState.putString(KEY_SHA256, checksums?.sha256)
        outState.putString(KEY_INPUT, binding.nameInput.text?.toString().orEmpty())
        outState.putBoolean(KEY_EXT_LOCKED, lockedExt != null)
        outState.putString(KEY_LOCKED_EXT, lockedExt.orEmpty())
        outState.putStringArrayList(KEY_HANDED_OUT, ArrayList(handedOutPaths))
        outState.putString(KEY_SAVE_PENDING, savePendingFile?.absolutePath)
        outState.putBoolean(KEY_WAS_SAVING, wasSaving || multiSaving || saveJob?.isActive == true)
        outState.putStringArrayList(KEY_MULTI_URIS, ArrayList(multiUris.map { it.toString() }))
        outState.putStringArrayList(KEY_MULTI_NAMES, ArrayList(multiNames))
        outState.putStringArrayList(KEY_MULTI_MIMES, ArrayList(multiMimes))
        outState.putStringArrayList(KEY_MULTI_PATHS, ArrayList(multiPaths))
        outState.putIntegerArrayList(KEY_MULTI_SAVED, ArrayList(multiSaved))
        outState.putBoolean(KEY_MULTI_AWAITING_TREE, multiAwaitingTree)
        // 超大文本不进 Bundle（TransactionTooLarge 会直接把界面重建打崩）；缓存文件还在时无需重放文本。
        outState.putBoolean(KEY_TEXT_UNKNOWN, textUnknown)
        if (textContent.length <= MAX_TEXT_STATE_CHARS) {
            outState.putString(KEY_TEXT_CONTENT, textContent)
            outState.putParcelable(KEY_SHARE_INTENT, originalShareIntent)
        }
    }

    override fun onStart() {
        super.onStart()
        backgroundJob?.cancel()
        backgroundJob = null
        if (sessionDir != null) CacheState.register(sessionDir!!)
        if (startedOnce) {
            // 从后台回来：缓存可能已经被 30 分钟宽限后的清理收走，自动重试一次复制。
            maybeRetryCopy()
        }
        startedOnce = true
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) {
            scheduleBackgroundGrace()
        }
    }

    override fun onDestroy() {
        // 让迟到进度回调立即失效，而不是等到新 Activity 换代
        operations.invalidate()
        cancelOperations()
        backgroundJob?.cancel()
        backgroundJob = null
        // 界面没了就不再是“活会话”：交过货的由交付标记保温，没交过货的留给下一次清理收走
        CacheState.unregister(sessionDir)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 入口解析

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) {
            if (!hasLoadedState()) showEmptyState()
            return
        }
        val action = intent.action
        val isShare = action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
        if (!isShare) {
            if (action == Intent.ACTION_MAIN) {
                // 启动器入口：已经加载了内容就不要清空（H1/L6）。
                if (!hasLoadedState()) showEmptyState()
                return
            }
            // 其它未知调用：按纯文本兜底，把原始请求转储成 .txt，同时保留原 Intent 供“发送”转发。
            loadText(
                content = describeIntent(intent),
                forwardIntent = Intent(intent),
                unknown = true,
            )
            return
        }

        val forwardIntent = Intent(intent)
        val allUris = collectAllUris(intent)
        val supported = allUris.filter(::isSupportedFileUri)
        val unsupported = allUris.filterNot(::isSupportedFileUri)
        val text = collectText(intent)
        when {
            supported.size > 1 -> loadMulti(supported)
            supported.size == 1 -> loadSource(supported.first())
            else -> {
                // 纯文本 / http(s) 等非文件 URI：保存成 UTF-8 文本文件，不再报“不支持”。
                val payload = buildTextPayload(text, unsupported)
                if (payload.isNotBlank()) {
                    loadText(payload, forwardIntent)
                } else {
                    // 既没有文件也没有文本：未知分享兜底，原始 Intent 转储成 txt。
                    loadText(describeIntent(intent), forwardIntent, unknown = true)
                }
            }
        }
    }

    private fun isSupportedFileUri(uri: Uri): Boolean =
        uri.scheme == "content" || uri.scheme == "file"

    /**
     * 读取 Intent 里的所有 URI（不做 scheme 过滤）。
     *
     * EXTRA_STREAM 可能是 Uri、ArrayList&lt;Uri&gt;、Parcelable[]，也可能是恶意/异常 Parcelable。
     * 旧实现用带泛型的 getParcelableExtra，遇到非 Uri 的 Parcelable 会在强转时崩溃；
     * 这里统一从 Bundle 取原始值再按类型筛选，所有解析异常都按“没有 URI”处理。
     */
    private fun collectAllUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        try {
            intent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index).uri?.let { out.add(it) }
                }
            }
        } catch (_: Throwable) {
            // 坏 Parcelable：当作没有 clipData
        }
        try {
            @Suppress("DEPRECATION")
            val raw = intent.extras?.get(Intent.EXTRA_STREAM)
            addStreamValue(raw, out)
        } catch (_: Throwable) {
            // BadParcelableException / ClassCastException 等：当作没有文件
        }
        return out.toList()
    }

    /** 纯文本分享的正文；没有 EXTRA_TEXT 时再看 ClipData item 的 text（有些应用只填后者） */
    private fun collectText(intent: Intent): String {
        var text = ""
        try {
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        } catch (_: Throwable) {
            // 坏 Parcelable：按没有文本处理
        }
        if (text.isNotBlank()) return text
        try {
            val clip = intent.clipData ?: return ""
            for (index in 0 until clip.itemCount) {
                val itemText = clip.getItemAt(index).text?.toString().orEmpty()
                if (itemText.isNotBlank()) return itemText
            }
        } catch (_: Throwable) {
            // 坏 Parcelable：按没有文本处理
        }
        return ""
    }

    /** 文本 + 非文件 URI 一起分享时，两者都保住：先正文，再补链接行 */
    private fun buildTextPayload(text: String, unsupported: List<Uri>): String {
        val link = unsupported.firstOrNull()?.toString().orEmpty()
        return when {
            text.isBlank() -> link
            link.isBlank() || text.contains(link) -> text
            else -> text + "\n\n" + link
        }
    }

    /**
     * 未知请求兜底：生成可保存的原始描述。优先用官方的 `Intent.toUri(URI_INTENT_SCHEME)`
     * 序列化（保留 action/type/data/extras/ClipData）；失败时退回 `toString()`。
     * 这里只用 `\n`，不碰原始文本的换行。
     */
    private fun describeIntent(intent: Intent): String {
        val raw = try {
            intent.toUri(Intent.URI_INTENT_SCHEME)
        } catch (_: Throwable) {
            try {
                intent.toString()
            } catch (_: Throwable) {
                "(unable to serialize intent)"
            }
        }
        val categories = try {
            intent.categories?.joinToString(", ").orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val extras = try {
            intent.extras?.keySet()?.joinToString(", ").orEmpty()
        } catch (_: Throwable) {
            ""
        }
        return buildString {
            append("Unrecognized share request").append('\n')
            append("action: ").append(intent.action ?: "(none)").append('\n')
            append("type: ").append(intent.type ?: "(none)").append('\n')
            append("data: ").append(intent.data?.toString() ?: "(none)").append('\n')
            append("flags: 0x").append(Integer.toHexString(intent.flags)).append('\n')
            append("categories: ").append(categories.ifEmpty { "(none)" }).append('\n')
            append("extras: ").append(extras.ifEmpty { "(none)" }).append('\n')
            append("raw: ").append(raw).append('\n')
        }
    }

    private fun addStreamValue(value: Any?, out: MutableSet<Uri>) {
        when (value) {
            is Uri -> out.add(value)
            is Array<*> -> value.filterIsInstance<Uri>().forEach(out::add)
            is Iterable<*> -> value.filterIsInstance<Uri>().forEach(out::add)
            else -> Unit
        }
    }

    private fun hasLoadedState(): Boolean =
        mode != ShareMode.NONE ||
            sourceUri != null ||
            currentFile != null ||
            originalName.isNotEmpty() ||
            multiUris.isNotEmpty() ||
            textContent.isNotEmpty() ||
            !binding.nameInput.text.isNullOrEmpty()

    // ---------------------------------------------------------------- 多文件 / 纯文本

    /**
     * 多文件分享：不再“只处理第一个”。文件名不可改，保存时逐个写进下载目录。
     * 元数据查询放在 IO；确认名称后再允许保存，避免用户点了保存却还不知道会存成什么。
     */
    private fun loadMulti(uris: List<Uri>) {
        val generation = operations.begin()
        cancelOperations()
        releaseSessionDir()

        mode = ShareMode.MULTI
        multiUris = uris
        multiNames = emptyList()
        multiMimes = emptyList()
        multiPaths = emptyList()
        multiSaved.clear()
        multiSaving = false
        multiAwaitingTree = false
        textPreparing = false
        textContent = ""
        textUnknown = false
        originalShareIntent = null
        sourceUri = null
        sourceMime = null
        sourceSize = -1L
        originalName = ""
        originalBase = ""
        originalExt = ""
        currentFile = null
        copyDone = false
        checksums = null
        lockedExt = null
        savePendingFile = null
        wasSaving = false
        preparingFile = false
        loadingMeta = false
        handedOutPaths.clear()

        setupMultiUi()
        binding.oldNameText.text = getString(R.string.old_multi_loading, uris.size)
        binding.progress.isIndeterminate = true
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        setButtonsEnabled(false)
        setStatus(getString(R.string.status_multi_reading, uris.size), isError = false)
        readMultiMeta(generation)
    }

    private fun setupMultiUi() {
        binding.oldNameLabel.setText(R.string.label_files_to_save)
        binding.pickButton.visibility = View.GONE
        binding.nameInputLayout.isEnabled = false
        binding.nameInput.isEnabled = false
        binding.allowExtCheck.setOnCheckedChangeListener(null)
        binding.allowExtCheck.isChecked = false
        binding.allowExtCheck.isEnabled = false
        binding.allowExtCheck.setOnCheckedChangeListener { _, isChecked -> applyExtLock(!isChecked) }
        binding.hintText.text = getString(R.string.hint_multi_mode)
        binding.warningText.visibility = View.GONE
        hideChecksums()
        setInput("")
    }

    private fun readMultiMeta(generation: Long) {
        loadingMeta = true
        val roots = multiUris
        lifecycleScope.launch {
            val entries = try {
                withContext(Dispatchers.IO) { expandMultiEntries(roots) }
            } catch (error: Throwable) {
                Log.w(TAG, "folder expansion failed", error)
                null
            }
            if (!isActive || !operations.isCurrent(generation) || mode != ShareMode.MULTI) return@launch
            loadingMeta = false
            if (entries == null) {
                copyDone = false
                binding.progress.visibility = View.GONE
                setButtonsEnabled(false)
                setStatus(getString(R.string.status_folder_unreadable), isError = true)
                return@launch
            }
            if (entries.isEmpty()) {
                copyDone = false
                binding.oldNameText.text = getString(R.string.status_multi_empty)
                binding.progress.visibility = View.GONE
                setButtonsEnabled(false)
                setStatus(getString(R.string.status_multi_empty), isError = true)
                return@launch
            }
            multiSaved.clear()
            multiUris = entries.map { it.uri }
            multiNames = entries.map { it.name }
            multiMimes = entries.map { mimeForSaving(FileNameUtils.split(it.name).ext, it.mime) }
            multiPaths = entries.map { it.relativeDir }
            copyDone = true
            binding.oldNameText.text = buildMultiList()
            binding.progress.visibility = View.GONE
            setButtonsEnabled(true)
            val count = entries.size
            setStatus(
                resources.getQuantityString(R.plurals.status_multi_ready, count, count),
                isError = false,
            )
        }
    }

    /** 展开多文件/文件夹来源；文件夹按 DocumentsProvider 的父子关系递归，保留相对目录 */
    private fun expandMultiEntries(roots: List<Uri>): List<ExpandedEntry> {
        val out = mutableListOf<ExpandedEntry>()
        roots.forEachIndexed { index, uri ->
            val meta = try {
                readSourceMeta(uri)
            } catch (_: Throwable) {
                SourceMeta("", -1L, null)
            }
            if (isDirectoryUri(uri, meta.mime)) {
                val rootName = folderComponent(meta.name, index)
                expandDirectory(uri, rootName, out, depth = 0)
            } else {
                out.add(
                    ExpandedEntry(
                        uri = uri,
                        relativeDir = "",
                        name = multiDisplayName(meta, index),
                        mime = meta.mime ?: mimeForExtension(FileNameUtils.split(meta.name).ext),
                    ),
                )
            }
        }
        return out
    }

    private fun isDirectoryMime(mime: String?): Boolean =
        mime == DocumentsContract.Document.MIME_TYPE_DIR || mime == "resource/folder"

    private fun isDirectoryUri(uri: Uri, mime: String?): Boolean {
        if (isDirectoryMime(mime)) return true
        return isDirectoryMime(queryDocumentMime(uri))
    }

    private fun queryDocumentMime(uri: Uri): String? {
        val target = try {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.buildDocumentUriUsingTree(
                    uri,
                    DocumentsContract.getTreeDocumentId(uri),
                )
            } else {
                uri
            }
        } catch (_: Throwable) {
            return null
        }
        return try {
            contentResolver.query(
                target,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun expandDirectory(
        uri: Uri,
        rootName: String,
        out: MutableList<ExpandedEntry>,
        depth: Int,
    ) {
        val tree = if (DocumentsContract.isTreeUri(uri)) uri else null
        val rootId = if (tree != null) {
            DocumentsContract.getTreeDocumentId(uri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }
        expandChildren(tree, uri.authority, rootId, rootName, out, depth)
    }

    private fun expandChildren(
        tree: Uri?,
        authority: String?,
        parentId: String,
        relativeDir: String,
        out: MutableList<ExpandedEntry>,
        depth: Int,
    ) {
        if (depth > MAX_FOLDER_DEPTH) throw IllegalStateException("folder too deep")
        if (out.size > MAX_MULTI_ENTRIES) throw IllegalStateException("too many files")
        val children = if (tree != null) {
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        } else {
            val auth = authority ?: throw IllegalStateException("missing authority")
            DocumentsContract.buildChildDocumentsUri(auth, parentId)
        }
        val cursor = contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        ) ?: return
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1).orEmpty()
                val mime = c.getString(2)
                val child = if (tree != null) {
                    DocumentsContract.buildDocumentUriUsingTree(tree, id)
                } else {
                    DocumentsContract.buildDocumentUri(authority!!, id)
                }
                if (isDirectoryMime(mime)) {
                    val childName = folderComponent(name, out.size)
                    expandChildren(
                        tree = tree,
                        authority = authority,
                        parentId = id,
                        relativeDir = relativeDir + "/" + childName,
                        out = out,
                        depth = depth + 1,
                    )
                } else {
                    out.add(
                        ExpandedEntry(
                            uri = child,
                            relativeDir = relativeDir,
                            name = multiDisplayName(SourceMeta(name, -1L, mime), out.size),
                            mime = mime ?: FALLBACK_MIME,
                        ),
                    )
                    if (out.size > MAX_MULTI_ENTRIES) {
                        throw IllegalStateException("too many files")
                    }
                }
            }
        }
    }

    private fun folderComponent(raw: String, index: Int): String {
        val fallback = getString(R.string.fallback_name) + "_" + (index + 1)
        val cleaned = FileNameUtils.sanitizeBase(raw)
            .ifEmpty { fallback }
        return FileNameUtils.truncateUtf8(cleaned, FileNameUtils.MAX_NAME_BYTES)
            .ifEmpty { fallback }
    }

    /** 多文件名只做清洗/长度限制，不改用户看到的名字；重名交给系统或目录内的唯一名逻辑 */
    private fun multiDisplayName(meta: SourceMeta, index: Int): String {
        val fallback = getString(R.string.fallback_name) + "_" + (index + 1)
        val parts = FileNameUtils.split(meta.name)
        val base = parts.base.ifBlank { fallback }
        return FileNameUtils.buildFinalName(base, parts.ext, fallback)
    }

    private fun buildMultiList(): String {
        if (multiNames.isEmpty()) return getString(R.string.old_multi_loading, multiUris.size)
        val shown = multiNames.indices.take(MULTI_LIST_LIMIT)
        return buildString {
            shown.forEachIndexed { position, index ->
                if (position > 0) append('\n')
                val path = multiPaths.getOrNull(index).orEmpty()
                if (path.isNotEmpty()) append(path).append('/')
                append(multiNames[index])
            }
            if (multiNames.size > shown.size) {
                append('\n').append(getString(R.string.multi_more, multiNames.size - shown.size))
            }
        }
    }

    /**
     * 纯文本 / 不支持的链接 / 未知请求：写成一个 UTF-8 `.txt` 缓存文件，沿用单文件的改名与保存流程；
     * “发送”不写文件，而是原样转发 [forwardIntent]（拿不到时用文本内容重建一个 ACTION_SEND）。
     */
    private fun loadText(
        content: String,
        forwardIntent: Intent? = null,
        unknown: Boolean = false,
    ) {
        val generation = operations.begin()
        cancelOperations()
        releaseSessionDir()

        mode = ShareMode.TEXT
        textContent = content
        textUnknown = unknown
        originalShareIntent = forwardIntent?.let { Intent(it) }
        multiUris = emptyList()
        multiNames = emptyList()
        multiMimes = emptyList()
        multiPaths = emptyList()
        multiSaved.clear()
        multiSaving = false
        multiAwaitingTree = false
        textPreparing = false

        val base = FileNameUtils.timestampBase()
        originalBase = base
        originalExt = ".txt"
        originalName = base + ".txt"
        sourceUri = null
        sourceMime = "text/plain"
        sourceSize = content.toByteArray(Charsets.UTF_8).size.toLong()
        currentFile = null
        copyDone = false
        checksums = null
        lockedExt = ".txt"
        savePendingFile = null
        wasSaving = false
        preparingFile = false
        loadingMeta = false
        handedOutPaths.clear()

        setupTextUi()
        setInput(base)
        prepareTextFile(generation)
    }

    private fun setupTextUi() {
        binding.oldNameLabel.setText(R.string.label_text_file_name)
        binding.oldNameText.text = sharedTextPreview(textContent)
        binding.pickButton.visibility = View.GONE
        binding.nameInputLayout.isEnabled = true
        binding.nameInput.isEnabled = true
        binding.allowExtCheck.setOnCheckedChangeListener(null)
        binding.allowExtCheck.isChecked = lockedExt == null
        binding.allowExtCheck.isEnabled = true
        binding.allowExtCheck.setOnCheckedChangeListener { _, isChecked -> applyExtLock(!isChecked) }
        binding.hintText.text = getString(
            if (textUnknown) R.string.hint_unknown_mode else R.string.hint_text_mode,
        )
        binding.warningText.visibility = View.GONE
        hideChecksums()
    }

    private fun sharedTextPreview(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return getString(R.string.old_text_received)
        val preview = if (trimmed.length > TEXT_PREVIEW_CHARS) {
            trimmed.take(TEXT_PREVIEW_CHARS) + "…"
        } else {
            trimmed
        }
        val label = when {
            textUnknown -> R.string.old_unknown_preview
            LINK_PATTERN.matches(trimmed) -> R.string.old_link_preview
            else -> R.string.old_text_preview
        }
        return getString(label, preview)
    }

    /** 把文本写进缓存；改名/校验/保存都复用单文件那套路径 */
    private fun prepareTextFile(generation: Long) {
        if (mode != ShareMode.TEXT || textContent.isEmpty()) {
            copyDone = false
            setButtonsEnabled(false)
            setStatus(getString(R.string.status_text_expired), isError = true)
            return
        }
        if (textPreparing) return
        textPreparing = true
        releaseSessionDir()
        binding.progress.isIndeterminate = true
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        setButtonsEnabled(false)
        setStatus(getString(R.string.status_text_preparing), isError = false)

        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { writeTextCache(textContent) }
            if (!isActive || !operations.isCurrent(generation) || mode != ShareMode.TEXT) return@launch
            textPreparing = false
            if (file == null) {
                copyDone = false
                currentFile = null
                binding.progress.visibility = View.GONE
                setButtonsEnabled(true)
                setStatus(getString(R.string.status_text_prepare_failed), isError = true)
                return@launch
            }
            currentFile = file
            sessionDir = file.parentFile
            copyDone = true
            sessionDir?.let { CacheState.register(it) }
            binding.progress.visibility = View.GONE
            setButtonsEnabled(true)
            setStatus(
                getString(
                    if (textUnknown) R.string.status_unknown_ready else R.string.status_text_ready,
                ),
                isError = false,
            )
        }
    }

    /** 只在 IO 线程调用；失败返回 null，调用方负责报错/重试 */
    private fun writeTextCache(content: String): File? {
        val dir = allocateSessionDir() ?: return null
        val target = File(dir, INCOMING_NAME + ".txt")
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return null
            target.writeBytes(content.toByteArray(Charsets.UTF_8))
            target.takeIf { it.isFile }
        } catch (_: Throwable) {
            dir.deleteRecursively()
            null
        }
    }

    /**
     * 加载一个新的分享源。Provider 查询（getType/query）全部放到 IO：
     * 慢 Provider 或恶意 Provider 不能再用 Binder 调用把主线程卡到 ANR（H4）。
     */
    private fun loadSource(uri: Uri) {
        val generation = operations.begin()
        cancelOperations()
        releaseSessionDir()
        loadingMeta = true

        mode = ShareMode.FILE
        multiUris = emptyList()
        multiNames = emptyList()
        multiMimes = emptyList()
        multiPaths = emptyList()
        multiSaved.clear()
        multiSaving = false
        multiAwaitingTree = false
        textPreparing = false
        textContent = ""
        textUnknown = false
        originalShareIntent = null
        binding.oldNameLabel.setText(R.string.label_original_name)
        binding.warningText.visibility = View.VISIBLE
        binding.nameInputLayout.isEnabled = true
        binding.nameInput.isEnabled = true

        sourceUri = uri
        sourceMime = null
        originalName = ""
        originalBase = ""
        originalExt = ""
        sourceSize = -1L
        currentFile = null
        copyDone = false
        checksums = null
        preparingFile = false
        wasSaving = false
        savePendingFile = null
        handedOutPaths.clear()

        hideChecksums()
        binding.oldNameText.text = getString(R.string.old_name_loading)
        binding.pickButton.visibility = View.GONE
        binding.allowExtCheck.setOnCheckedChangeListener(null)
        binding.allowExtCheck.isChecked = false
        binding.allowExtCheck.isEnabled = false
        binding.allowExtCheck.setOnCheckedChangeListener { _, isChecked -> applyExtLock(!isChecked) }
        setInput("")
        binding.hintText.text = getString(R.string.hint_no_ext)
        binding.progress.isIndeterminate = true
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        setButtonsEnabled(false)
        setStatus(getString(R.string.status_copying), isError = false)

        lifecycleScope.launch {
            val meta = withContext(Dispatchers.IO) { readSourceMeta(uri) }
            if (!isActive || !operations.isCurrent(generation)) return@launch
            loadingMeta = false

            // 分享的是文件夹时切到多文件/文件夹保存流程；单文件仍走原来的改名/发送。
            val directory = withContext(Dispatchers.IO) { isDirectoryUri(uri, meta.mime) }
            if (!isActive || !operations.isCurrent(generation)) return@launch
            if (directory) {
                loadMulti(listOf(uri))
                return@launch
            }

            sourceMime = meta.mime
            sourceSize = meta.size
            var display = meta.name
            if (display.isBlank()) display = getString(R.string.fallback_name)
            originalName = display
            val parts = FileNameUtils.split(display)
            originalBase = parts.base
            originalExt = parts.ext

            binding.oldNameText.text = originalName
            binding.pickButton.visibility = View.GONE
            lockedExt = originalExt
            binding.allowExtCheck.setOnCheckedChangeListener(null)
            binding.allowExtCheck.isChecked = false
            binding.allowExtCheck.isEnabled = originalExt.isNotEmpty()
            binding.allowExtCheck.setOnCheckedChangeListener { _, isChecked -> applyExtLock(!isChecked) }
            setInput(originalBase)
            updateHint()

            setStatus(getString(R.string.status_copying), isError = false)
            startCopy(uri, generation)
        }
    }

    /** 源文件的名字、大小与 MIME（SIZE 拿不到时返回 -1：pipe 型 Provider 报不出来） */
    private class SourceMeta(val name: String, val size: Long, val mime: String?)

    /** 展开文件夹后的一个待保存文件；relativeDir 是相对目录（不含 Download 根） */
    private class ExpandedEntry(
        val uri: Uri,
        val relativeDir: String,
        val name: String,
        val mime: String,
    )

    private fun readSourceMeta(uri: Uri): SourceMeta {
        val mime = try {
            contentResolver.getType(uri)
        } catch (_: Throwable) {
            null
        }
        // 树 URI 本身不一定支持 OpenableColumns 查询；根文档 URI 才稳定提供 DISPLAY_NAME/SIZE。
        val queryUri = try {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.buildDocumentUriUsingTree(
                    uri,
                    DocumentsContract.getTreeDocumentId(uri),
                )
            } else {
                uri
            }
        } catch (_: Throwable) {
            uri
        }
        var name = ""
        var size = -1L
        try {
            contentResolver.query(
                queryUri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        name = cursor.getString(nameIndex).orEmpty()
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Throwable) {
            // 走下面的兜底
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        }
        return SourceMeta(name.trim(), size, mime)
    }

    // ---------------------------------------------------------------- 状态恢复

    /**
     * 从 savedInstanceState 恢复完整会话：源/文件/输入/扩展名锁定/交付路径/SAF 待保存文件。
     * 复制没完成或文件已经被清掉时自动重试；保存被中断时只提示、不自动重放（避免重复写 Downloads）。
     */
    private fun restoreSession(state: Bundle) {
        val generation = operations.begin()
        val hadSource = state.getBoolean(KEY_HAS_SOURCE, false)
        if (!hadSource) {
            showEmptyState()
            return
        }
        val savedInput = state.getString(KEY_INPUT)
        val savedInputForEmpty = savedInput.orEmpty()

        when (state.getString(KEY_MODE)) {
            ShareMode.MULTI.name -> {
                restoreMulti(state, generation)
                return
            }
            ShareMode.TEXT.name -> {
                restoreText(state, savedInputForEmpty, generation)
                return
            }
        }
        mode = ShareMode.FILE

        @Suppress("DEPRECATION")
        sourceUri = state.getString(KEY_SOURCE_URI)?.let(Uri::parse)
        sourceMime = state.getString(KEY_SOURCE_MIME)
        originalName = state.getString(KEY_ORIGINAL_NAME).orEmpty()
        originalBase = state.getString(KEY_ORIGINAL_BASE).orEmpty()
        originalExt = state.getString(KEY_ORIGINAL_EXT).orEmpty()
        sourceSize = state.getLong(KEY_SOURCE_SIZE, -1L)
        copyDone = state.getBoolean(KEY_COPY_DONE, false)
        checksums = readChecksums(state)

        val savedDir = state.getString(KEY_SESSION_DIR)?.let(::File)
        sessionDir = savedDir?.takeIf { isSessionDir(it) }
        val savedFile = state.getString(KEY_CURRENT_FILE)?.let(::File)
        currentFile = savedFile?.takeIf { it.isFile && isUnderSharedRoot(it) }
        if (sessionDir == null) {
            sessionDir = currentFile?.parentFile?.takeIf { isSessionDir(it) }
        }
        if (currentFile == null || checksums == null) copyDone = false

        lockedExt = if (state.getBoolean(KEY_EXT_LOCKED, false)) {
            state.getString(KEY_LOCKED_EXT).orEmpty()
        } else {
            null
        }
        handedOutPaths.clear()
        state.getStringArrayList(KEY_HANDED_OUT)?.forEach { handedOutPaths.add(it) }

        val savedPending = state.getString(KEY_SAVE_PENDING)?.let(::File)
        savePendingFile = savedPending?.takeIf { it.isFile && isUnderSharedRoot(it) }

        binding.oldNameText.text =
            if (originalName.isBlank()) getString(R.string.old_name_none) else originalName
        binding.pickButton.visibility = if (sourceUri != null) View.GONE else View.VISIBLE
        binding.allowExtCheck.setOnCheckedChangeListener(null)
        binding.allowExtCheck.isChecked = lockedExt == null
        binding.allowExtCheck.isEnabled = originalExt.isNotEmpty()
        binding.allowExtCheck.setOnCheckedChangeListener { _, isChecked -> applyExtLock(!isChecked) }
        setInput(savedInput ?: originalBase)
        updateHint()
        binding.progress.visibility = View.GONE
        lastPercent.set(-1)
        lastReportedBytes.set(0L)
        if (copyDone && checksums != null) showChecksums(checksums!!) else hideChecksums()

        if (savePendingFile != null) {
            // SAF 选择器还开着（或结果还没回来）：恢复待保存文件，等回调。
            saveGeneration = generation
            wasSaving = true
            setButtonsEnabled(false)
            setStatus(getString(R.string.status_saving), isError = false)
            if (sessionDir != null) CacheState.register(sessionDir!!)
            return
        }

        if (copyDone && currentFile != null) {
            if (sessionDir != null) CacheState.register(sessionDir!!)
            setButtonsEnabled(true)
            setStatus(
                getString(
                    if (state.getBoolean(KEY_WAS_SAVING, false)) {
                        R.string.status_save_interrupted
                    } else {
                        R.string.status_ready
                    },
                ),
                isError = state.getBoolean(KEY_WAS_SAVING, false),
            )
            return
        }

        val uri = sourceUri
        if (uri != null) {
            // 复制没完成：保留输入，自动重试；失败时状态栏会明确提示。
            setButtonsEnabled(false)
            setStatus(getString(R.string.status_copying), isError = false)
            startCopy(uri, generation)
            return
        }

        showEmptyState()
        // 只有输入、没有文件的状态也保留用户已经敲进去的名字
        if (savedInputForEmpty.isNotEmpty()) setInput(savedInputForEmpty)
    }

    private fun restoreMulti(state: Bundle, generation: Long) {
        mode = ShareMode.MULTI
        multiUris = state.getStringArrayList(KEY_MULTI_URIS)
            ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .orEmpty()
        multiNames = state.getStringArrayList(KEY_MULTI_NAMES).orEmpty()
        multiMimes = state.getStringArrayList(KEY_MULTI_MIMES).orEmpty()
        multiPaths = state.getStringArrayList(KEY_MULTI_PATHS).orEmpty()
        if (multiPaths.size != multiNames.size) {
            multiPaths = List(multiNames.size) { "" }
        }
        multiSaved.clear()
        state.getIntegerArrayList(KEY_MULTI_SAVED)
            ?.filter { it in multiUris.indices }
            ?.let { multiSaved.addAll(it) }
        multiAwaitingTree = state.getBoolean(KEY_MULTI_AWAITING_TREE, false)
        multiSaving = false
        sourceUri = null
        sourceMime = null
        sourceSize = -1L
        originalName = ""
        originalBase = ""
        originalExt = ""
        currentFile = null
        sessionDir = null
        copyDone = multiUris.isNotEmpty() &&
            multiNames.size == multiUris.size &&
            multiPaths.size == multiUris.size
        checksums = null
        lockedExt = null
        savePendingFile = null
        textContent = ""
        textUnknown = false
        originalShareIntent = null
        handedOutPaths.clear()

        setupMultiUi()
        binding.progress.visibility = View.GONE
        if (multiUris.isEmpty()) {
            showEmptyState()
            setStatus(getString(R.string.status_multi_empty), isError = true)
            return
        }
        if (copyDone) binding.oldNameText.text = buildMultiList()
        if (multiAwaitingTree) {
            saveGeneration = generation
            wasSaving = true
            setButtonsEnabled(false)
            setStatus(getString(R.string.status_saving), isError = false)
            return
        }
        if (!copyDone) {
            binding.oldNameText.text = getString(R.string.old_multi_loading, multiUris.size)
            binding.progress.visibility = View.VISIBLE
            setButtonsEnabled(false)
            setStatus(getString(R.string.status_multi_reading, multiUris.size), isError = false)
            readMultiMeta(generation)
            return
        }
        setButtonsEnabled(true)
        if (state.getBoolean(KEY_WAS_SAVING, false)) {
            setStatus(getString(R.string.status_save_interrupted), isError = true)
        } else {
            val count = multiUris.size
            setStatus(
                resources.getQuantityString(R.plurals.status_multi_ready, count, count),
                isError = false,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreText(state: Bundle, savedInput: String, generation: Long) {
        mode = ShareMode.TEXT
        textContent = state.getString(KEY_TEXT_CONTENT).orEmpty()
        textUnknown = state.getBoolean(KEY_TEXT_UNKNOWN, false)
        originalShareIntent = state.getParcelable<Intent>(KEY_SHARE_INTENT)
        originalName = state.getString(KEY_ORIGINAL_NAME).orEmpty()
        originalBase = state.getString(KEY_ORIGINAL_BASE).orEmpty()
            .ifEmpty { FileNameUtils.timestampBase() }
        originalExt = state.getString(KEY_ORIGINAL_EXT).orEmpty().ifEmpty { ".txt" }
        if (originalName.isEmpty()) originalName = originalBase + originalExt
        sourceUri = null
        sourceMime = state.getString(KEY_SOURCE_MIME) ?: "text/plain"
        sourceSize = state.getLong(KEY_SOURCE_SIZE, -1L)
        val savedFile = state.getString(KEY_CURRENT_FILE)?.let(::File)
        currentFile = savedFile?.takeIf { it.isFile && isUnderSharedRoot(it) }
        sessionDir = state.getString(KEY_SESSION_DIR)?.let(::File)
            ?.takeIf { isSessionDir(it) }
            ?: currentFile?.parentFile?.takeIf { isSessionDir(it) }
        copyDone = currentFile != null
        checksums = null
        lockedExt = if (state.getBoolean(KEY_EXT_LOCKED, true)) {
            state.getString(KEY_LOCKED_EXT)?.takeIf { it.isNotEmpty() } ?: ".txt"
        } else {
            null
        }
        val savedPending = state.getString(KEY_SAVE_PENDING)?.let(::File)
        savePendingFile = savedPending?.takeIf { it.isFile && isUnderSharedRoot(it) }
        multiUris = emptyList()
        multiNames = emptyList()
        multiMimes = emptyList()
        multiPaths = emptyList()
        multiSaved.clear()
        multiSaving = false
        multiAwaitingTree = false
        textPreparing = false
        handedOutPaths.clear()

        setupTextUi()
        setInput(savedInput.ifEmpty { originalBase })
        binding.progress.visibility = View.GONE

        if (savePendingFile != null) {
            saveGeneration = generation
            wasSaving = true
            setButtonsEnabled(false)
            setStatus(getString(R.string.status_saving), isError = false)
            sessionDir?.let { CacheState.register(it) }
            return
        }
        if (copyDone) {
            sessionDir?.let { CacheState.register(it) }
            setButtonsEnabled(true)
            setStatus(
                getString(
                    if (state.getBoolean(KEY_WAS_SAVING, false)) {
                        R.string.status_save_interrupted
                    } else if (textUnknown) {
                        R.string.status_unknown_ready
                    } else {
                        R.string.status_text_ready
                    },
                ),
                isError = state.getBoolean(KEY_WAS_SAVING, false),
            )
            return
        }
        if (textContent.isNotEmpty()) {
            prepareTextFile(generation)
            return
        }
        copyDone = false
        setButtonsEnabled(false)
        setStatus(getString(R.string.status_text_expired), isError = true)
    }

    private fun isUnderSharedRoot(file: File): Boolean = try {
        val root = sharedRoot.canonicalPath
        val path = file.canonicalPath
        path.startsWith(root + File.separator)
    } catch (_: Throwable) {
        false
    }

    private fun isSessionDir(dir: File): Boolean = try {
        dir.isDirectory && dir.parentFile?.canonicalPath == sharedRoot.canonicalPath
    } catch (_: Throwable) {
        false
    }

    // ---------------------------------------------------------------- 复制到缓存

    /**
     * 分配一个全新的会话目录名。目录名带时间戳 + 会话号 + UUID：
     * 时钟回拨/进程重启/同毫秒都不会复用旧目录。只生成名字，不创建目录。
     */
    private fun allocateSessionDir(): File? {
        var dir: File
        var attempts = 0
        do {
            dir = File(sharedRoot, CacheStore.newSessionName(System.currentTimeMillis(), ++sessionCounter))
            attempts++
        } while (dir.exists() && attempts < 4)
        return dir.takeUnless { it.exists() }
    }

    private fun startCopy(uri: Uri, generation: Long = operations.begin()) {
        if (!operations.isCurrent(generation)) return
        copyJob?.cancel()
        releaseSessionDir()
        startCleanup(force = false)
        loadingMeta = false
        checksums = null
        hashFinishedGeneration = -1L
        hideChecksums()

        val dir = allocateSessionDir()
        if (dir == null) {
            copyDone = false
            currentFile = null
            setButtonsEnabled(true)
            setStatus(getString(R.string.status_copy_failed), isError = true)
            return
        }

        // 临时文件名只借用规范化后的扩展名：originalExt 直接来自对方应用的 DISPLAY_NAME，
        // 可能超长或含 `/ \ :` 等字符，不能原样拼进路径
        val target = File(dir, INCOMING_NAME + FileNameUtils.sanitizeExt(originalExt))
        sessionDir = dir
        currentFile = target
        copyDone = false
        checksums = null
        hideChecksums()
        finishedGeneration = -1L
        lastPercent.set(-1)
        lastReportedBytes.set(0L)
        CacheState.register(dir)

        binding.progress.isIndeterminate = true
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        setButtonsEnabled(false)

        val copySize = sourceSize
        copyJob = lifecycleScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    // mkdirs 也是磁盘操作：放到 IO，失败走统一失败路径。
                    if (!dir.isDirectory && !dir.mkdirs()) {
                        return@withContext CopyOutcome.FAILED
                    }
                    ensureRoomThenCopy(dir, uri, target, copySize, generation) {
                        !operations.isCurrent(generation)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                CopyOutcome.FAILED
            }

            // 会话已换代或本协程被取消：什么都不许碰，避免把新状态覆盖掉
            if (!isActive || !operations.isCurrent(generation)) return@launch
            finishedGeneration = generation

            if (outcome == CopyOutcome.OK) {
                // 复制成功后再回读缓存文件算校验值：这是真正会被分享/保存的那份文件。
                // 如果写盘时发生损坏，用户拿这里的值和源文件对比就能发现。
                binding.progress.isIndeterminate = true
                binding.progress.progress = 0
                binding.progress.visibility = View.VISIBLE
                lastPercent.set(-1)
                hashPercent.set(-1)
                hashFinishedGeneration = -1L
                setStatus(getString(R.string.status_hashing), isError = false)

                val hashes = try {
                    withContext(Dispatchers.IO) {
                        FileDigestUtils.hashFile(
                            file = target,
                            cancelled = { !operations.isCurrent(generation) },
                        ) { read, total -> onHashProgress(generation, read, total) }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    null
                }

                if (!isActive || !operations.isCurrent(generation)) return@launch
                hashFinishedGeneration = generation
                if (hashes == null) {
                    target.delete()
                    currentFile = null
                    releaseSessionDir()
                    copyDone = false
                    binding.progress.visibility = View.GONE
                    setButtonsEnabled(true)
                    setStatus(getString(R.string.status_hash_failed), isError = true)
                    return@launch
                }

                checksums = hashes
                showChecksums(hashes)
                copyDone = true
                binding.progress.visibility = View.GONE
                setButtonsEnabled(true)
                setStatus(getString(R.string.status_ready), isError = false)
                return@launch
            }

            // 复制失败：不保留半套校验值，按钮保持可点以便重试。
            binding.progress.visibility = View.GONE
            copyDone = false
            hideChecksums()
            setButtonsEnabled(true)
            when (outcome) {
                CopyOutcome.NO_SPACE -> {
                    currentFile = null
                    releaseSessionDir()
                    setStatus(getString(R.string.status_storage_low), isError = true)
                }
                CopyOutcome.TOO_LARGE -> {
                    currentFile = null
                    releaseSessionDir()
                    setStatus(getString(R.string.status_too_large), isError = true)
                }
                CopyOutcome.SOURCE_UNAVAILABLE -> {
                    currentFile = null
                    releaseSessionDir()
                    setStatus(getString(R.string.status_source_unavailable), isError = true)
                }
                CopyOutcome.FAILED, CopyOutcome.OK -> {
                    currentFile = null
                    releaseSessionDir()
                    setStatus(getString(R.string.status_copy_failed), isError = true)
                }
            }
        }
    }

    /**
     * 复制进度回调，来自 IO 线程，已做节流。
     * [generation] 是发起本次复制时的代际：旧代际的迟到回调、以及本代际已经出过
     * 最终结果之后的回调，一律丢弃——这正是“文案卡在 100% 不显示复制完成”的根源。
     */
    private fun onCopyProgress(generation: Long, copied: Long, total: Long) {
        if (total <= 0L) {
            val previous = lastReportedBytes.get()
            if (copied - previous < PROGRESS_BYTE_STEP) return
            if (!lastReportedBytes.compareAndSet(previous, copied)) return
            val text = getString(R.string.status_copying_size, formatSize(copied))
            runOnUiThread {
                if (isStale(generation)) return@runOnUiThread
                setStatus(text, isError = false)
            }
            return
        }
        val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
        if (lastPercent.getAndSet(percent) == percent) return
        runOnUiThread {
            if (isStale(generation)) return@runOnUiThread
            binding.progress.isIndeterminate = false
            binding.progress.progress = percent
            setStatus(getString(R.string.status_copying_percent, percent), isError = false)
        }
    }

    /** 回读文件的校验值进度；与复制进度分开，避免 finishedGeneration 把哈希进度丢掉。 */
    private fun onHashProgress(generation: Long, read: Long, total: Long) {
        if (total <= 0L || !operations.isCurrent(generation)) return
        val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
        if (hashPercent.getAndSet(percent) == percent) return
        runOnUiThread {
            if (!operations.isCurrent(generation) || generation == hashFinishedGeneration) {
                return@runOnUiThread
            }
            binding.progress.isIndeterminate = false
            binding.progress.progress = percent
            setStatus(getString(R.string.status_hashing_percent, percent), isError = false)
        }
    }

    /** 旧代际（已被取代/取消）或已经出过最终结果的代际：迟到的进度更新一律丢弃 */
    private fun isStale(generation: Long): Boolean =
        !operations.isCurrent(generation) || generation == finishedGeneration

    private fun showChecksums(value: FileChecksums) {
        binding.checksumMd5.text = getString(R.string.checksum_md5_format, value.md5)
        binding.checksumSha1.text = getString(R.string.checksum_sha1_format, value.sha1)
        binding.checksumSha256.text = getString(R.string.checksum_sha256_format, value.sha256)
        binding.checksumSection.visibility = View.VISIBLE
    }

    private fun hideChecksums() {
        binding.checksumSection.visibility = View.GONE
        binding.checksumMd5.text = ""
        binding.checksumSha1.text = ""
        binding.checksumSha256.text = ""
    }

    private fun readChecksums(state: Bundle): FileChecksums? {
        val md5 = state.getString(KEY_MD5)
        val sha1 = state.getString(KEY_SHA1)
        val sha256 = state.getString(KEY_SHA256)
        if (md5 == null || sha1 == null || sha256 == null) return null
        if (md5.length != 32 || sha1.length != 40 || sha256.length != 64) return null
        return FileChecksums(md5, sha1, sha256)
    }

    private fun copyChecksumsToClipboard() {
        val value = checksums ?: return
        val text = getString(
            R.string.checksum_copy_all_format,
            value.md5,
            value.sha1,
            value.sha256,
        )
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.checksum_title), text),
        )
        Toast.makeText(applicationContext, R.string.toast_checksums_copied, Toast.LENGTH_SHORT).show()
    }

    /** 复制结果：只有 [OK] 才允许继续交付 */
    private enum class CopyOutcome { OK, NO_SPACE, TOO_LARGE, SOURCE_UNAVAILABLE, FAILED }

    /**
     * 复制前的空间预检 + 复制。
     *
     * cacheDir 在 /data 里，把一份比剩余空间还大的文件（4K 视频很常见）复制进来会把内部存储写满：
     * 轻则复制失败，重则拖累整个系统。而“空间不足”里有相当一部分其实是被旧会话占着，
     * 所以先强行清一次再复核，还是不够才明确告诉用户是空间不足。
     *
     * 只在 IO 线程调用。
     */
    private fun ensureRoomThenCopy(
        dir: File,
        uri: Uri,
        target: File,
        need: Long,
        generation: Long,
        cancelled: () -> Boolean,
    ): CopyOutcome {
        if (need > 0L && !CacheCleaner.hasRoomFor(writableSpace(dir), need)) {
            cleanupCache(force = true)
            if (!CacheCleaner.hasRoomFor(writableSpace(dir), need)) return CopyOutcome.NO_SPACE
        }
        // 已知大小按实际大小校验；未知/报 0 的流用 2GB 与“剩余空间 - 动态预留”的较小值兜底。
        val maxBytes = if (need > 0L) {
            -1L
        } else {
            CacheCleaner.maxBytesForUnknownStream(writableSpace(dir))
        }
        return copyUriToFile(uri, target, cancelled, maxBytes, generation)
    }

    /**
     * 判断「放不放得下」用的可写空间。
     *
     * 只看 `usableSpace` 会在“缓存很多、剩余空间紧张”时误报空间不足——而本应用自己的缓存
     * 正是系统可以随手清掉的那部分。API 26+ 问一次系统「算上可清理的缓存实际能腾出多少」，
     * 只查询、不真的 allocateBytes：真腾空间让系统按需做，我们不替它决定清谁。
     */
    private fun writableSpace(dir: File): Long {
        val raw = dir.usableSpace
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return raw
        return try {
            val manager = getSystemService(StorageManager::class.java) ?: return raw
            maxOf(raw, manager.getAllocatableBytes(manager.getUuidForPath(dir)))
        } catch (_: Throwable) {
            // 个别 ROM 上拿不到 UUID / 查询失败：退回裸 usableSpace，绝不因此拦住复制
            raw
        }
    }

    /**
     * 复制源 URI 到本地文件。
     *
     * 快路径用 file descriptor + FileChannel.transferTo；但 transferTo 允许“少传”甚至返回 0，
     * 所以**只有 statSize > 0 时才走它**，并且必须用 statSize 校验字节数：
     *  - statSize == 0：不再走“空文件成功”快路径，统一读流到 EOF，避免 pseudo-file 报 0 但有内容；
     *  - statSize < 0（管道型 Provider）：直接复用已经打开的 PFD 做流复制，不重复 open；
     *  - 快路径校验不符：丢掉半成品，改走流复制。
     * 两条兜底路径都不会把不完整的文件当成成功。
     */
    private fun copyUriToFile(
        uri: Uri,
        target: File,
        cancelled: () -> Boolean,
        maxBytes: Long,
        generation: Long,
    ): CopyOutcome {
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd == null) {
                return streamCopyFromUri(uri, target, cancelled, maxBytes, generation)
            }
            val statSize = try {
                pfd.statSize
            } catch (_: Throwable) {
                -1L
            }
            if (statSize > 0L) {
                // Provider 可能报 SIZE=-1（所以走了未知大小上限），但 statSize 却是一个很大的值；
                // 在写任何字节之前用同一上限拦掉，避免“先写满磁盘再失败”。
                if (maxBytes >= 0L && statSize > maxBytes) {
                    target.delete()
                    return CopyOutcome.TOO_LARGE
                }
                val fast = tryFastCopy(pfd, target, statSize, cancelled, generation)
                if (fast == CopyOutcome.OK) return CopyOutcome.OK
                target.delete()
                if (cancelled()) return CopyOutcome.FAILED
                // 快路径失败/少传：先关掉 PFD，再重新开流从 0 开始复制。
                // 对“只允许同时打开一次”的 Provider，不先关会导致重开直接失败。
                try {
                    pfd.close()
                } catch (_: Throwable) {
                    // ignore
                }
                pfd = null
                return streamCopyFromUri(uri, target, cancelled, maxBytes, generation)
            }
            // statSize <= 0：空文件或未知大小，统一走流复制，读到 EOF 才算完成。
            // 复用已经打开的 PFD，避免对“只允许打开一次”的 Provider 再开一次流。
            return FileInputStream(pfd.fileDescriptor).use { input ->
                streamCopy(
                    input = input,
                    target = target,
                    cancelled = cancelled,
                    maxBytes = maxBytes,
                    generation = generation,
                )
            }
        } catch (error: Throwable) {
            target.delete()
            return outcomeFor(error)
        } finally {
            if (pfd != null) {
                try {
                    pfd.close()
                } catch (_: Throwable) {
                    // fd 已经由 FileInputStream 关闭时这里会失败，忽略
                }
            }
        }
    }

    /** 快路径：transferTo 可能返回 0/少传，必须用 statSize 校验，失败由调用方退回流复制 */
    private fun tryFastCopy(
        pfd: ParcelFileDescriptor,
        target: File,
        statSize: Long,
        cancelled: () -> Boolean,
        generation: Long,
    ): CopyOutcome {
        var complete = false
        try {
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
                        onCopyProgress(generation, total, statSize)
                    }
                    complete = !cancelled() && total == statSize
                }
            }
        } catch (_: Throwable) {
            complete = false
        }
        return if (complete) CopyOutcome.OK else CopyOutcome.FAILED
    }

    private fun streamCopyFromUri(
        uri: Uri,
        target: File,
        cancelled: () -> Boolean,
        maxBytes: Long,
        generation: Long,
    ): CopyOutcome = try {
        val input = contentResolver.openInputStream(uri) ?: return CopyOutcome.FAILED
        input.use { streamCopy(it, target, cancelled, maxBytes, generation) }
    } catch (error: Throwable) {
        target.delete()
        outcomeFor(error)
    }

    /** 兜底复制：带上限、可取消，读到 EOF 才算完整 */
    private fun streamCopy(
        input: InputStream,
        target: File,
        cancelled: () -> Boolean,
        maxBytes: Long,
        generation: Long,
    ): CopyOutcome {
        val result = try {
            FileOutputStream(target).use { output ->
                StreamCopier.copy(
                    input = input,
                    output = output,
                    maxBytes = maxBytes,
                    cancelled = cancelled,
                ) { copied -> onCopyProgress(generation, copied, -1L) }
            }
        } catch (_: Throwable) {
            target.delete()
            return CopyOutcome.FAILED
        }
        return when (result.outcome) {
            StreamCopier.Outcome.OK -> CopyOutcome.OK
            StreamCopier.Outcome.TOO_LARGE -> {
                target.delete()
                CopyOutcome.TOO_LARGE
            }
            StreamCopier.Outcome.CANCELLED -> {
                target.delete()
                CopyOutcome.FAILED
            }
            StreamCopier.Outcome.FAILED -> {
                target.delete()
                outcomeFor(result.error)
            }
        }
    }

    private fun outcomeFor(error: Throwable?): CopyOutcome = when {
        error == null -> CopyOutcome.FAILED
        error is SecurityException || error is FileNotFoundException -> CopyOutcome.SOURCE_UNAVAILABLE
        CacheCleaner.isNoSpace(error) -> CopyOutcome.NO_SPACE
        else -> CopyOutcome.FAILED
    }

    /**
     * 同目录复制（只在“已经交付过的文件”需要改名时使用，避免打破对方正在读的 URI）。
     * 用 copyTo 逐块复制并校验字节数，避免 transferTo 瞬时返回 0 导致改名失败。
     */
    private fun copyFile(source: File, target: File): Boolean {
        val expected = source.length()
        val copied = try {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
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
        when (mode) {
            ShareMode.FILE -> prepareThenAct { file, generation ->
                startShare(file, generation)
            }
            ShareMode.TEXT -> forwardOriginalShare()
            ShareMode.MULTI, ShareMode.NONE -> Unit
        }
    }

    /**
     * 纯文本/未知兜底模式的“发送”：原样转发来源 Intent，不经过缓存文件、不改 EXTRA_TEXT。
     * 只在 ClipData/EXTRA_STREAM 存在时补一个读取授权 flag，否则目标应用读不到 URI。
     */
    private fun forwardOriginalShare() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            setStatus(getString(R.string.status_resume_to_continue), isError = true)
            setButtonsEnabled(true)
            return
        }
        val original = originalShareIntent ?: if (textContent.isNotEmpty()) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, textContent)
            }
        } else {
            setStatus(getString(R.string.status_share_failed), isError = true)
            return
        }
        val forward = Intent(original)
        if (forward.clipData != null || forward.hasExtra(Intent.EXTRA_STREAM)) {
            forward.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            forward,
            getString(R.string.chooser_forward_title),
        ).apply {
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(this@MainActivity, MainActivity::class.java)),
            )
        }
        try {
            startActivity(chooser)
            finishWithToast(getString(R.string.toast_forwarded))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_share_app, Toast.LENGTH_LONG).show()
            setButtonsEnabled(true)
        } catch (_: Throwable) {
            setStatus(getString(R.string.status_share_failed), isError = true)
            setButtonsEnabled(true)
        }
    }

    /** 把改名后的文件交给系统分享面板；只有 startActivity 成功才算真正交付 */
    private suspend fun startShare(file: File, generation: Long) {
        if (!operations.isCurrent(generation)) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            setStatus(getString(R.string.status_resume_to_continue), isError = true)
            setButtonsEnabled(copyDone)
            return
        }
        val finalName = file.name
        val shareUri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (_: Throwable) {
            setStatus(getString(R.string.status_rename_failed), isError = true)
            setButtonsEnabled(copyDone)
            return
        }
        val session = file.parentFile?.name
        if (session == null) {
            setStatus(getString(R.string.status_rename_failed), isError = true)
            setButtonsEnabled(copyDone)
            return
        }

        // 决策 A：先写交付标记，再启动分享面板。
        // 进程在 startActivity 之后、标记写完之前被杀，文件就没有跨进程保护；
        // 现在如果标记写不进去，直接不发分享，避免把死链交给对方 App。
        val protected = try {
            withContext(Dispatchers.IO) {
                CacheStore.markDelivered(stateRoot, session, System.currentTimeMillis())
            }
        } catch (cancel: CancellationException) {
            // 标记可能已经写下去了，但界面在这时被销毁：回滚后再把取消继续抛出去。
            withContext(NonCancellable + Dispatchers.IO) {
                CacheStore.clearDelivered(stateRoot, session)
            }
            throw cancel
        }
        if (!protected) {
            setStatus(getString(R.string.status_cache_protect_failed), isError = true)
            setButtonsEnabled(copyDone)
            return
        }
        if (!operations.isCurrent(generation)) {
            // 标记已经写了但还没交出去：回滚，别让旧会话无谓保温 24 小时。
            withContext(NonCancellable + Dispatchers.IO) { CacheStore.clearDelivered(stateRoot, session) }
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
        val chooser = Intent.createChooser(sendIntent, getString(R.string.chooser_title)).apply {
            // 排除自己：分享面板里不再出现 HelloRename，避免用户形成自循环（L3）
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(this@MainActivity, MainActivity::class.java)),
            )
        }

        val started = try {
            startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_share_app, Toast.LENGTH_LONG).show()
            false
        } catch (_: Throwable) {
            setStatus(getString(R.string.status_share_failed), isError = true)
            false
        }
        if (!started) {
            withContext(NonCancellable + Dispatchers.IO) { CacheStore.clearDelivered(stateRoot, session) }
            setButtonsEnabled(copyDone)
            return
        }

        // 真的交出去了：登记这条路径“别人可能在读”，之后不许再 rename/删除它
        handedOutPaths.add(file.absolutePath)
        if (operations.isCurrent(generation)) {
            // Toast 提醒“选别的应用”，然后关掉主界面，防止用户回头又选本应用
            finishWithToast(getString(R.string.toast_sent, getString(R.string.app_name)))
        }
    }

    /**
     * 「发送」和「保存到下载」的公共前置：先把改名落到缓存文件上，再执行 [onReady]。
     *
     * 改名里的重复复制（大文件）和 rename 全部丢到 IO 线程，不再卡主线程；
     * 期间禁用两个按钮防连点。是否算“交付过”由 [onReady] 决定：只有真正交付成功
     * 才会把路径登记进 [handedOutPaths]，取消/失败不会。
     */
    private fun prepareThenAct(onReady: suspend (File, Long) -> Unit) {
        if (mode == ShareMode.MULTI) return
        val uri = sourceUri
        if (mode != ShareMode.TEXT && uri == null) {
            setStatus(getString(R.string.status_no_file), isError = true)
            return
        }
        val file = currentFile
        if (!copyDone || file == null || !file.exists()) {
            if (mode == ShareMode.TEXT) {
                prepareTextFile(operations.current())
            } else {
                // 复制没成功或被清掉了：重新复制一次（复制失败后按钮保持可点，就是走这条路重试）
                setStatus(getString(R.string.status_copying), isError = false)
                startCopy(uri!!)
            }
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
        val generation = operations.current()
        setButtonsEnabled(false)
        prepareJob = lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    prepareFinalFile(snapshot) { !operations.isCurrent(generation) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                PrepareResult.RenameFailed
            } finally {
                if (operations.isCurrent(generation)) preparingFile = false
            }
            // 改名期间又分享了新文件：这次结果作废，界面状态交给新会话
            if (!operations.isCurrent(generation)) return@launch
            when (result) {
                is PrepareResult.Ready -> {
                    currentFile = result.file
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        // 用户已经按 Home：不要从后台启动分享面板/SAF 选择器（L7）。
                        setStatus(getString(R.string.status_resume_to_continue), isError = true)
                        setButtonsEnabled(copyDone)
                        return@launch
                    }
                    onReady(result.file, generation)
                }
                PrepareResult.NeedCopy -> {
                    if (mode == ShareMode.TEXT) {
                        prepareTextFile(generation)
                    } else {
                        setStatus(getString(R.string.status_copying), isError = false)
                        startCopy(uri!!)
                    }
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
    private fun prepareFinalFile(
        snapshot: RenameSnapshot,
        cancelled: () -> Boolean,
    ): PrepareResult {
        if (cancelled()) return PrepareResult.NeedCopy
        val file = snapshot.file
        if (!file.exists()) return PrepareResult.NeedCopy

        val (rawBase, rawExt) = namePartsFor(snapshot)
        val finalName = FileNameUtils.buildFinalName(rawBase, rawExt, snapshot.fallbackBase)
        val target = File(snapshot.sessionDir ?: file.parentFile, finalName)

        if (target.absolutePath != file.absolutePath) {
            if (target.exists()) {
                // 目标名字已经被交付出去：绝不能删掉它，否则对方的 URI 会变死链。
                if (snapshot.handedOut.contains(target.absolutePath)) {
                    return PrepareResult.RenameFailed
                }
                if (!target.delete()) return PrepareResult.RenameFailed
            }
            val succeeded = if (snapshot.handedOut.contains(file.absolutePath)) {
                // 旧名字已经交给别人了，动它会让对方正在读的 URI 失效，所以另复制一份
                copyFile(file, target)
            } else {
                file.renameTo(target)
            }
            if (!succeeded) return PrepareResult.RenameFailed
        }
        if (cancelled()) return PrepareResult.NeedCopy
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

    private fun mimeFromExtension(ext: String): String? {
        val key = FileNameUtils.extensionKey(ext)
        if (key.isEmpty()) return null
        return try {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(key)
        } catch (_: Throwable) {
            null
        }
    }

    private fun mimeForExtension(ext: String): String =
        mimeFromExtension(ext) ?: FALLBACK_MIME

    /**
     * 保存时用的 MIME：源 MIME 的规范扩展名和当前扩展名冲突时改用扩展名推断的 MIME，
     * 避免 MediaStore 把 `desktop.ini` 保存成 `desktop.ini.txt`（text/plain → txt）。
     */
    private fun mimeForSaving(ext: String, source: String?): String {
        val canonical = try {
            source
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        } catch (_: Throwable) {
            null
        }
        return FileNameUtils.chooseSaveMime(
            ext = ext,
            sourceMime = source,
            canonicalExt = canonical,
            extMime = mimeFromExtension(ext),
            fallback = FALLBACK_MIME,
        )
    }

    private fun resolveMime(ext: String): String {
        return if (ext.equals(originalExt, ignoreCase = true)) {
            mimeForSaving(ext, sourceMime)
        } else {
            mimeForExtension(ext)
        }
    }

    // ---------------------------------------------------------------- 保存到下载

    private fun onSaveClicked() {
        when (mode) {
            ShareMode.FILE, ShareMode.TEXT -> prepareThenAct { file, generation ->
                val finalName = file.name
                val mime = resolveMime(FileNameUtils.split(finalName).ext)
                binding.saveButton.isEnabled = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(file, finalName, mime, generation)
                } else {
                    saveViaSaf(file, finalName, mime, generation)
                }
            }
            ShareMode.MULTI -> saveMulti()
            ShareMode.NONE -> setStatus(getString(R.string.status_no_file), isError = true)
        }
    }

    private sealed interface SaveResult {
        data class Success(val uri: Uri) : SaveResult
        data object Cancelled : SaveResult
        data class Failed(val error: Throwable?) : SaveResult
    }

    // ---------------------------------------------------------------- 多文件保存

    private fun saveMulti() {
        if (mode != ShareMode.MULTI || multiSaving || multiAwaitingTree) return
        if (multiUris.isEmpty() || !copyDone) return
        if (multiNames.size != multiUris.size || multiPaths.size != multiUris.size) return
        val generation = operations.current()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runMultiSave(generation) { index ->
                insertUriIntoDownloads(
                    source = multiUris[index],
                    displayName = multiNames[index],
                    mime = multiMimes.getOrElse(index) { FALLBACK_MIME },
                    relativeDir = multiPaths.getOrElse(index) { "" },
                    cancelled = { !operations.isCurrent(generation) },
                )
            }
        } else {
            launchMultiTreePicker(generation)
        }
    }

    /**
     * Android 7–9 没有 MediaStore.Downloads，也不能一次 CREATE_DOCUMENT 建多个文档：
     * 让用户选一个目录，再逐个 createDocument。目录授权只用于当前这次保存。
     */
    private fun launchMultiTreePicker(generation: Long) {
        multiAwaitingTree = true
        saveGeneration = generation
        wasSaving = true
        setButtonsEnabled(false)
        setStatus(getString(R.string.status_multi_pick_folder, multiUris.size), isError = false)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsDocumentUri())
            }
        }
        try {
            pickMultiTree.launch(intent)
        } catch (_: Throwable) {
            multiAwaitingTree = false
            wasSaving = false
            setButtonsEnabled(true)
            setStatus(getString(R.string.status_save_failed), isError = true)
        }
    }

    /** 多文件保存的公共循环：已成功的下标跳过，部分失败可只补失败的 */
    private fun runMultiSave(generation: Long, saveOne: suspend (Int) -> Boolean) {
        if (multiSaving) return
        multiSaving = true
        wasSaving = true
        setButtonsEnabled(false)
        binding.progress.isIndeterminate = true
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE

        saveJob = lifecycleScope.launch {
            val total = multiUris.size
            try {
                for (index in 0 until total) {
                    if (!operations.isCurrent(generation) || mode != ShareMode.MULTI) return@launch
                    if (index in multiSaved) continue
                    setStatus(getString(R.string.status_multi_saving, index + 1, total), isError = false)
                    val ok = try {
                        withContext(Dispatchers.IO) { saveOne(index) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Throwable) {
                        false
                    }
                    if (!operations.isCurrent(generation) || mode != ShareMode.MULTI) return@launch
                    if (ok) multiSaved.add(index)
                }
                if (!operations.isCurrent(generation) || mode != ShareMode.MULTI) return@launch
                binding.progress.visibility = View.GONE
                val remaining = total - multiSaved.size
                if (remaining == 0) {
                    finishWithToast(
                        resources.getQuantityString(R.plurals.toast_saved_multi, total, total),
                    )
                } else {
                    setButtonsEnabled(true)
                    setStatus(
                        getString(R.string.status_multi_partial, multiSaved.size, total, remaining),
                        isError = true,
                    )
                }
            } finally {
                if (operations.isCurrent(generation) && mode == ShareMode.MULTI) {
                    multiSaving = false
                    wasSaving = false
                    if (!isFinishing) binding.progress.visibility = View.GONE
                }
            }
        }
    }

    /**
     * 多文件在 Android 10+ 的单个保存单元：直接读源 URI 写进 MediaStore，
     * 不经过缓存（不需要改名，也没有校验值）。失败会删除 pending 条目。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertUriIntoDownloads(
        source: Uri,
        displayName: String,
        mime: String,
        relativeDir: String,
        cancelled: () -> Boolean,
    ): Boolean {
        if (cancelled()) return false
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, downloadRelativePath(relativeDir))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val out = try {
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (_: Throwable) {
            null
        } ?: return false
        pendingMediaUri = out
        var success = false
        return try {
            val output = contentResolver.openOutputStream(out)
                ?: throw IOException("openOutputStream returned null")
            output.use { outStream ->
                val input = contentResolver.openInputStream(source)
                    ?: throw IOException("openInputStream returned null")
                input.use { inStream ->
                    val copied = StreamCopier.copy(inStream, outStream, cancelled = cancelled)
                    if (copied.outcome == StreamCopier.Outcome.CANCELLED) return false
                    if (copied.outcome != StreamCopier.Outcome.OK) {
                        throw copied.error ?: IOException("copy failed: ${copied.outcome}")
                    }
                }
            }
            if (cancelled()) return false
            val updated = contentResolver.update(
                out,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (updated <= 0) throw IOException("IS_PENDING update returned $updated")
            success = true
            true
        } catch (_: Throwable) {
            false
        } finally {
            pendingMediaUri = null
            if (!success) {
                try {
                    contentResolver.delete(out, null, null)
                } catch (_: Throwable) {
                    // 清理失败就留给系统回收
                }
            }
        }
    }

    private fun startMultiTreeSave(tree: Uri, generation: Long) {
        runMultiSave(generation) { index ->
            saveUriToTree(
                tree = tree,
                source = multiUris[index],
                displayName = multiNames[index],
                mime = multiMimes.getOrElse(index) { FALLBACK_MIME },
                relativeDir = multiPaths.getOrElse(index) { "" },
                generation = generation,
            )
        }
    }

    private fun saveUriToTree(
        tree: Uri,
        source: Uri,
        displayName: String,
        mime: String,
        relativeDir: String,
        generation: Long,
    ): Boolean {
        val parent = ensureTreeDirectory(tree, relativeDir) ?: return false
        val parentId = try {
            DocumentsContract.getDocumentId(parent)
        } catch (_: Throwable) {
            return false
        }
        val name = uniqueTreeName(tree, parentId, displayName)
        val document = try {
            DocumentsContract.createDocument(contentResolver, parent, mime, name)
        } catch (_: Throwable) {
            null
        } ?: return false
        var success = false
        return try {
            val output = contentResolver.openOutputStream(document)
                ?: throw IOException("openOutputStream returned null")
            output.use { outStream ->
                val input = contentResolver.openInputStream(source)
                    ?: throw IOException("openInputStream returned null")
                input.use { inStream ->
                    val copied = StreamCopier.copy(
                        inStream,
                        outStream,
                        cancelled = { !operations.isCurrent(generation) },
                    )
                    if (copied.outcome != StreamCopier.Outcome.OK) {
                        throw copied.error ?: IOException("copy failed: ${copied.outcome}")
                    }
                }
            }
            success = true
            true
        } catch (_: Throwable) {
            false
        } finally {
            if (!success) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, document)
                } catch (_: Throwable) {
                    // 删除失败时保留可能是半成品；状态栏会明确提示有文件失败
                }
            }
        }
    }

    /**
     * 把相对目录拼成 MediaStore 的 RELATIVE_PATH。MediaProvider 会为父目录执行 mkdirs，
     * 所以嵌套路径可以直接写；这里再做一次逐段清洗，避免 `..` 或分隔符逃逸。
     */
    private fun downloadRelativePath(relativeDir: String): String {
        val parts = relativeDir.split('/')
            .map { FileNameUtils.sanitizeBase(it) }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) {
            Environment.DIRECTORY_DOWNLOADS + "/"
        } else {
            Environment.DIRECTORY_DOWNLOADS + "/" + parts.joinToString("/") + "/"
        }
    }

    /** 在 SAF 目标树里逐级查找/创建目录，返回最终父目录的 document URI */
    private fun ensureTreeDirectory(tree: Uri, relativeDir: String): Uri? {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: Throwable) {
            return null
        }
        var parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        var parentId = rootId
        val parts = relativeDir.split('/')
            .map { FileNameUtils.sanitizeBase(it) }
            .filter { it.isNotEmpty() }
        for (part in parts) {
            val existing = findTreeChild(tree, parentId, part)
            if (existing != null) {
                parent = existing.first
                parentId = existing.second
                continue
            }
            val name = uniqueTreeName(tree, parentId, part)
            val created = try {
                DocumentsContract.createDocument(
                    contentResolver,
                    parent,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    name,
                )
            } catch (_: Throwable) {
                null
            } ?: return null
            parent = created
            parentId = try {
                DocumentsContract.getDocumentId(created)
            } catch (_: Throwable) {
                return null
            }
        }
        return parent
    }

    /** 在 SAF 目录里按名字找子目录（忽略大小写），找不到返回 null */
    private fun findTreeChild(
        tree: Uri,
        parentId: String,
        displayName: String,
    ): Pair<Uri, String>? {
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                        name.equals(displayName, ignoreCase = true)
                    ) {
                        val id = cursor.getString(0) ?: return@use null
                        return@use DocumentsContract.buildDocumentUriUsingTree(tree, id) to id
                    }
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 目录内重名时先自己编号，避免某些 DocumentsProvider 的 createDocument 直接失败。
     * 查询失败就退回原名，由 Provider 自己处理。
     */
    private fun uniqueTreeName(tree: Uri, parentId: String, displayName: String): String {
        val existing = mutableSetOf<String>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { existing.add(it.lowercase(Locale.US)) }
                }
            }
        } catch (_: Throwable) {
            return displayName
        }
        if (existing.isEmpty()) return displayName
        if (displayName.lowercase(Locale.US) !in existing) return displayName
        val parts = FileNameUtils.split(displayName)
        for (index in 1..31) {
            val candidate = parts.base + " (" + index + ")" + parts.ext
            if (candidate.lowercase(Locale.US) !in existing) return candidate
        }
        return parts.base + " (" + System.currentTimeMillis() + ")" + parts.ext
    }

    /**
     * Android 10+：MediaStore 直写公共下载目录，零权限、无弹窗。
     * IS_PENDING 期间条目对其他应用不可见，写完再转正；重名由系统自动编号，不会覆盖已有文件。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        source: File,
        displayName: String,
        mime: String,
        generation: Long,
    ) {
        if (!operations.isCurrent(generation)) return
        wasSaving = true
        setStatus(getString(R.string.status_saving), isError = false)
        binding.progress.isIndeterminate = true
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        setButtonsEnabled(false)

        saveJob = lifecycleScope.launch {
            var out: Uri? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    insertIntoDownloads(source, displayName, mime, { !operations.isCurrent(generation) }) { uri ->
                        out = uri
                        pendingMediaUri = uri
                    }
                }
                if (!operations.isCurrent(generation)) return@launch
                wasSaving = false
                binding.progress.visibility = View.GONE
                when (result) {
                    is SaveResult.Success -> {
                        withContext(Dispatchers.IO) { markDelivered(source) }
                        handedOutPaths.add(source.absolutePath)
                        finishWithToast(
                            getString(R.string.toast_saved, FileNameUtils.ellipsizeForToast(displayName)),
                        )
                    }
                    SaveResult.Cancelled -> {
                        setStatus(getString(R.string.status_save_canceled), isError = false)
                        setButtonsEnabled(copyDone)
                    }
                    is SaveResult.Failed -> {
                        setStatus(getString(R.string.status_save_failed), isError = true)
                        Toast.makeText(
                            applicationContext,
                            R.string.toast_save_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                        setButtonsEnabled(copyDone)
                    }
                }
            } catch (cancel: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { out?.let { deleteMedia(it) } }
                throw cancel
            } catch (_: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { out?.let { deleteMedia(it) } }
                if (operations.isCurrent(generation)) {
                    wasSaving = false
                    binding.progress.visibility = View.GONE
                    setStatus(getString(R.string.status_save_failed), isError = true)
                    Toast.makeText(
                        applicationContext,
                        R.string.toast_save_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    setButtonsEnabled(copyDone)
                }
            } finally {
                pendingMediaUri = null
            }
        }
    }

    /**
     * 只在 IO 线程调用。任何一步失败都删掉 pending 条目；`update(IS_PENDING=0)` 返回 <= 0
     * 也算失败，否则会留下 Downloads 里看不见、界面却提示“已保存”的孤儿文件。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertIntoDownloads(
        source: File,
        displayName: String,
        mime: String,
        cancelled: () -> Boolean,
        onPending: (Uri) -> Unit,
    ): SaveResult {
        if (cancelled()) return SaveResult.Cancelled
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val out = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (error: Throwable) {
            return SaveResult.Failed(error)
        } ?: return SaveResult.Failed(null)
        onPending(out)

        var success = false
        return try {
            val stream = resolver.openOutputStream(out)
                ?: throw IOException("openOutputStream returned null")
            stream.use { output ->
                FileInputStream(source).use { input ->
                    val copied = StreamCopier.copy(input, output, cancelled = cancelled)
                    if (copied.outcome == StreamCopier.Outcome.CANCELLED) return SaveResult.Cancelled
                    if (copied.outcome != StreamCopier.Outcome.OK) {
                        throw copied.error ?: IOException("copy failed: ${copied.outcome}")
                    }
                }
            }
            if (cancelled()) return SaveResult.Cancelled
            val updated = resolver.update(
                out,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (updated <= 0) throw IOException("IS_PENDING update returned $updated")
            success = true
            SaveResult.Success(out)
        } catch (error: Throwable) {
            if (cancelled()) SaveResult.Cancelled else SaveResult.Failed(error)
        } finally {
            if (!success) {
                try {
                    resolver.delete(out, null, null)
                } catch (_: Throwable) {
                    // 清理失败就留给系统回收
                }
            }
        }
    }

    private fun deleteMedia(uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
        } catch (_: Throwable) {
            // ignore
        }
    }

    /**
     * Android 7–9：SAF 选择器（这个区间零权限的唯一稳妥做法；直接写文件要
     * WRITE_EXTERNAL_STORAGE，会破坏本项目的零权限承诺，也会挂掉 QA 体检的权限门禁）。
     */
    private fun saveViaSaf(
        source: File,
        displayName: String,
        mime: String,
        generation: Long,
    ) {
        if (savePendingFile != null) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            setStatus(getString(R.string.status_resume_to_continue), isError = true)
            setButtonsEnabled(copyDone)
            return
        }
        savePendingFile = source
        saveGeneration = generation
        wasSaving = true
        setStatus(getString(R.string.status_saving), isError = false)
        setButtonsEnabled(false)
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
            wasSaving = false
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

    private class WriteResult(
        val ok: Boolean,
        val bytes: Long,
        val incomplete: Boolean,
    )

    /**
     * 把缓存文件流式写到 SAF 返回的 URI。
     *
     * 先试 "wt"（截断写，不残留旧内容）；只有**一个字节都没写进去**时才允许回退 "w"。
     * 旧实现无条件回退：如果 "wt" 已经写了一半才失败，而目标 Provider 对 "w" 不做截断，
     * 就会留下“半截旧内容 + 完整新内容”的混合文件。
     */
    private fun writeToUri(
        source: File,
        dest: Uri,
        cancelled: () -> Boolean,
    ): WriteResult {
        val first = writeToUriWithMode(source, dest, "wt", cancelled)
        if (first.ok) return first
        if (first.bytes > 0L || cancelled()) {
            cleanupDocument(dest)
            return first
        }
        val second = writeToUriWithMode(source, dest, "w", cancelled)
        if (second.ok) return second
        // 失败后尽量删除/截断目标；删不掉也要明确告诉用户可能不完整。
        cleanupDocument(dest)
        return WriteResult(
            ok = false,
            bytes = second.bytes,
            incomplete = second.bytes > 0L || first.bytes > 0L,
        )
    }

    private fun writeToUriWithMode(
        source: File,
        dest: Uri,
        mode: String,
        cancelled: () -> Boolean,
    ): WriteResult {
        var written = 0L
        return try {
            val output = contentResolver.openOutputStream(dest, mode)
                ?: return WriteResult(false, 0L, false)
            output.use { out ->
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var zeroReads = 0
                    while (true) {
                        if (cancelled()) return WriteResult(false, written, written > 0L)
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) {
                            zeroReads++
                            if (zeroReads > MAX_ZERO_READS) {
                                return WriteResult(false, written, written > 0L)
                            }
                            Thread.yield()
                            continue
                        }
                        zeroReads = 0
                        out.write(buffer, 0, read)
                        written += read
                    }
                }
            }
            val expected = source.length()
            if (cancelled() || (expected >= 0L && written != expected)) {
                WriteResult(false, written, incomplete = written > 0L)
            } else {
                WriteResult(true, written, incomplete = false)
            }
        } catch (_: Throwable) {
            WriteResult(false, written, incomplete = written > 0L)
        }
    }

    private fun cleanupDocument(uri: Uri) {
        try {
            if (DocumentsContract.deleteDocument(contentResolver, uri)) return
        } catch (_: Throwable) {
            // 有些 DocumentsProvider 不支持 deleteDocument：再尝试以 "wt" 重开并关闭来截断
        }
        try {
            contentResolver.openOutputStream(uri, "wt")?.use {
                // 关闭即完成截断
            }
        } catch (_: Throwable) {
            // 清理不了就只能让调用方明确报“可能不完整”
        }
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
            mode == ShareMode.MULTI -> getString(R.string.hint_multi_mode)
            mode == ShareMode.TEXT && locked != null -> getString(R.string.hint_text_mode)
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

    /**
     * 「发送」与「保存到下载」共用的按钮开关（复制/改名期间整体禁用，防连点）。
     * 多文件/纯文本是“只保存”模式，发送按钮始终禁用。
     */
    private fun setButtonsEnabled(enabled: Boolean) {
        binding.sendButton.isEnabled =
            enabled && (mode == ShareMode.FILE || mode == ShareMode.TEXT)
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

    private fun showEmptyState() {
        operations.begin()
        cancelOperations()
        mode = ShareMode.NONE
        sourceUri = null
        sourceMime = null
        originalName = ""
        originalBase = ""
        originalExt = ""
        sourceSize = -1L
        multiUris = emptyList()
        multiNames = emptyList()
        multiMimes = emptyList()
        multiPaths = emptyList()
        multiSaved.clear()
        multiSaving = false
        multiAwaitingTree = false
        textPreparing = false
        textContent = ""
        textUnknown = false
        originalShareIntent = null
        currentFile = null
        releaseSessionDir()
        copyDone = false
        checksums = null
        handedOutPaths.clear()
        lockedExt = ""
        savePendingFile = null
        wasSaving = false
        loadingMeta = false
        preparingFile = false
        lastPercent.set(-1)
        lastReportedBytes.set(0L)

        hideChecksums()
        binding.oldNameLabel.setText(R.string.label_original_name)
        binding.oldNameText.text = getString(R.string.old_name_none)
        binding.nameInputLayout.isEnabled = true
        binding.nameInput.isEnabled = true
        binding.warningText.visibility = View.VISIBLE
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

    // ---------------------------------------------------------------- 缓存清理

    /**
     * 触发一次缓存清理。**清理永远在 IO 线程跑**：删除是真实磁盘操作，旧实现放在 onCreate 里
     * 同步做，攒了几个 >1 小时的大文件就会卡住冷启动（甚至 ANR）。
     */
    private fun startCleanup(force: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { cleanupCache(force) }
        }
    }

    /** 只在 IO 线程调用；[force] 见 [CacheState.shouldCleanup] */
    private fun cleanupCache(force: Boolean) {
        val shared = sharedRoot
        // 先看目录在不在：目录都没有就别白吃掉一次节流窗口
        if (!shared.isDirectory) return
        val now = System.currentTimeMillis()
        if (!CacheState.shouldCleanup(now, force)) return
        try {
            val report = CacheStore.cleanup(shared, stateRoot, CacheState.livePaths(), now)
            if (report.deleted > 0 || report.failed > 0) {
                // 旧实现把一切都吞掉，线上出问题无从下手，这里至少留一行可查
                Log.i(
                    TAG,
                    "cache cleanup: deleted=${report.deleted} freed=${report.freedBytes}B" +
                        " keptLive=${report.keptLive} keptFresh=${report.keptFresh}" +
                        " failed=${report.failed}",
                )
            }
        } catch (error: Throwable) {
            // 清理失败无关紧要，但不能把异常带进分享流程
            Log.w(TAG, "cache cleanup failed", error)
        }
    }

    /**
     * 把这个会话标成“已交付”：清理时按 [CacheCleaner.DELIVERED_TTL_MS] 保温，
     * 而不是按“没交出去”的短 TTL 清掉——对方 App 可能还在异步读这个文件。
     * 只在 IO 线程调用。
     */
    private fun markDelivered(file: File) {
        val dir = file.parentFile ?: return
        // 只认 shared/<会话>/<文件> 这一层，避免给奇怪路径写状态
        if (dir.parentFile?.absolutePath != sharedRoot.absolutePath) return
        if (!CacheStore.markDelivered(stateRoot, dir.name, System.currentTimeMillis())) {
            // 写不进去就只剩内存里的 handedOutPaths 保护，进程一结束就失效：记一笔，别让它无声无息
            Log.w(TAG, "mark delivered failed: session=${dir.name}")
        }
    }

    /** 放弃当前会话目录：撤掉活会话登记，之后交给清理按规则收走 */
    private fun releaseSessionDir() {
        CacheState.unregister(sessionDir)
        sessionDir = null
    }

    // ---------------------------------------------------------------- 后台宽限 / 取消

    /**
     * 用户按 Home 后不再立即释放会话：给 30 分钟宽限，期间返回还能继续用。
     * 30 分钟后如果复制/保存都不在跑，才撤掉活会话登记，让 10 分钟未交付 TTL 生效。
     */
    private fun scheduleBackgroundGrace() {
        backgroundJob?.cancel()
        backgroundJob = lifecycleScope.launch {
            while (isActive) {
                delay(BACKGROUND_GRACE_MS)
                val busy = copyJob?.isActive == true ||
                    prepareJob?.isActive == true ||
                    saveJob?.isActive == true ||
                    preparingFile ||
                    loadingMeta ||
                    savePendingFile != null
                if (busy) continue
                CacheState.unregister(sessionDir)
                break
            }
        }
    }

    /** 从后台回来时缓存可能已被清掉：自动重试复制一次，不让界面停在空文件上 */
    private fun maybeRetryCopy() {
        if (isFinishing || loadingMeta) return
        if (savePendingFile != null || multiSaving || multiAwaitingTree) return
        if (copyJob?.isActive == true || prepareJob?.isActive == true || saveJob?.isActive == true) return
        when (mode) {
            ShareMode.MULTI -> Unit
            ShareMode.TEXT -> {
                val file = currentFile
                if (!copyDone || file == null || !file.isFile) {
                    prepareTextFile(operations.current())
                }
            }
            ShareMode.FILE -> {
                val uri = sourceUri ?: return
                val file = currentFile
                if (!copyDone || file == null || !file.isFile) {
                    setStatus(getString(R.string.status_copying), isError = false)
                    startCopy(uri)
                }
            }
            ShareMode.NONE -> Unit
        }
    }

    /** 统一取消所有异步操作，并清理它们可能留下的 MediaStore/SAF 半成品 */
    private fun cancelOperations() {
        copyJob?.cancel()
        copyJob = null
        prepareJob?.cancel()
        prepareJob = null
        saveJob?.cancel()
        saveJob = null
        preparingFile = false
        wasSaving = false
        multiSaving = false
        multiAwaitingTree = false
        textPreparing = false
        val media = pendingMediaUri
        pendingMediaUri = null
        if (media != null) {
            cleanupScope.launch { deleteMedia(media) }
        }
    }

    private companion object {
        const val TAG = "hellorename"
        const val INCOMING_NAME = "_incoming"
        const val FALLBACK_MIME = "application/octet-stream"
        const val CHUNK_SIZE = 1048576L          // 1 MiB
        const val COPY_BUFFER = 65536
        const val MAX_STALLS = 3
        const val MAX_ZERO_READS = 100
        const val PROGRESS_BYTE_STEP = 4194304L  // 4 MiB
        const val BACKGROUND_GRACE_MS = 30 * 60 * 1000L
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val MULTI_LIST_LIMIT = 8
        const val MAX_FOLDER_DEPTH = 32
        const val MAX_MULTI_ENTRIES = 2000
        const val TEXT_PREVIEW_CHARS = 300
        const val MAX_TEXT_STATE_CHARS = 100_000
        val LINK_PATTERN = Regex("(?i)^(https?|ftp)://\\S+$")

        const val KEY_HAS_SOURCE = "has_source"
        const val KEY_MODE = "mode"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_SOURCE_MIME = "source_mime"
        const val KEY_ORIGINAL_NAME = "original_name"
        const val KEY_ORIGINAL_BASE = "original_base"
        const val KEY_ORIGINAL_EXT = "original_ext"
        const val KEY_SOURCE_SIZE = "source_size"
        const val KEY_SESSION_DIR = "session_dir"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_COPY_DONE = "copy_done"
        const val KEY_MD5 = "checksum_md5"
        const val KEY_SHA1 = "checksum_sha1"
        const val KEY_SHA256 = "checksum_sha256"
        const val KEY_INPUT = "input"
        const val KEY_EXT_LOCKED = "ext_locked"
        const val KEY_LOCKED_EXT = "locked_ext"
        const val KEY_HANDED_OUT = "handed_out"
        const val KEY_SAVE_PENDING = "save_pending"
        const val KEY_WAS_SAVING = "was_saving"
        const val KEY_MULTI_URIS = "multi_uris"
        const val KEY_MULTI_NAMES = "multi_names"
        const val KEY_MULTI_MIMES = "multi_mimes"
        const val KEY_MULTI_PATHS = "multi_paths"
        const val KEY_MULTI_SAVED = "multi_saved"
        const val KEY_MULTI_AWAITING_TREE = "multi_awaiting_tree"
        const val KEY_TEXT_CONTENT = "text_content"
        const val KEY_TEXT_UNKNOWN = "text_unknown"
        const val KEY_SHARE_INTENT = "share_intent"
    }

    private enum class ShareMode { NONE, FILE, MULTI, TEXT }
}
