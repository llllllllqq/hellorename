package moe.hellorename

import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * API 36 模拟器上的主流程 / 生命周期测试（由 QA 与 Release 的模拟器门禁执行）。
 *
 * 用 FileProvider content:// URI 提供测试源文件，覆盖：
 *  - 分享进入 → 复制完成 → 显示 MD5/SHA-1/SHA-256；
 *  - “打开方式”（ACTION_VIEW）与单文件分享走同一条路；
 *  - 配置重建后输入/旧文件名/复制状态/校验值不丢（H1）；
 *  - ACTION_MAIN 重进不清空当前文件（L6）；
 *  - 异常 EXTRA_STREAM 不崩溃（L5）；
 *  - Parcelable[] 形式的 EXTRA_STREAM 仍能识别。
 */
@RequiresApi(android.os.Build.VERSION_CODES.Q)
@RunWith(AndroidJUnit4::class)
class MainActivityFlowTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun clearCache() {
        CacheState.resetForTest()
        File(context.cacheDir, CacheStore.SHARED_DIR).deleteRecursively()
        File(context.cacheDir, CacheStore.STATE_DIR).deleteRecursively()
    }

    @Test
    fun sendIntent_copiesFile_andRestoresInputAfterRecreate() {
        val source = newSource()
        val displayName = source.name

        ActivityScenario.launch<MainActivity>(sendIntent(uriFor(source))).use { scenario ->
            waitUntilCopied(scenario)
            onView(withId(R.id.oldNameText)).check(matches(withText(displayName)))
            onView(withId(R.id.nameInput)).perform(replaceText("renamed-file"), closeSoftKeyboard())

            scenario.recreate()

            onView(withId(R.id.nameInput)).check(matches(withText("renamed-file")))
            onView(withId(R.id.oldNameText)).check(matches(withText(displayName)))
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
            onView(withId(R.id.sendButton)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun mainIntent_doesNotClearLoadedFile() {
        val source = newSource()
        val displayName = source.name
        val originalIntent = sendIntent(uriFor(source))

        ActivityScenario.launch<MainActivity>(originalIntent).use { scenario ->
            waitUntilCopied(scenario)
            onView(withId(R.id.nameInput)).perform(replaceText("keep-me"), closeSoftKeyboard())

            scenario.onActivity { activity ->
                instrumentation.callActivityOnNewIntent(activity, Intent(Intent.ACTION_MAIN))
            }

            onView(withId(R.id.nameInput)).check(matches(withText("keep-me")))
            onView(withId(R.id.oldNameText)).check(matches(withText(displayName)))
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))

            // ActivityScenario 只接受与启动 Intent 匹配的生命周期事件；onNewIntent 里 setIntent
            // 换成 MAIN 后它会把随后的 PAUSED/STOPPED/DESTROYED 全部忽略，close() 就会超时。
            // 这是测试框架的限制，恢复启动 Intent 让 close() 正常收尾。
            scenario.onActivity { it.intent = originalIntent }
        }
    }

    @Test
    fun malformedExtraStream_fallsBackToRawText() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, Bundle().apply { putString("not", "a uri") })
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntilSaveEnabled(scenario)
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
            onView(withId(R.id.saveButton)).check(matches(isEnabled()))
            scenario.onActivity { activity ->
                val preview = activity.findViewById<TextView>(R.id.oldNameText).text.toString()
                assertTrue("malformed share should be captured as raw text", preview.contains("Unrecognized share request"))
            }
        }
    }

    @Test
    fun parcelableArrayExtraStream_isAccepted() {
        val source = newSource()
        val displayName = source.name
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, arrayOf(uriFor(source)))
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntilCopied(scenario)
            onView(withId(R.id.oldNameText)).check(matches(withText(displayName)))
        }
    }

    @Test
    fun textShare_switchesToTextMode_andKeepsEditedNameAfterRecreate() {
        val source = newSource()
        val originalIntent = sendIntent(uriFor(source))
        ActivityScenario.launch<MainActivity>(originalIntent).use { scenario ->
            waitUntilCopied(scenario)
            onView(withId(R.id.nameInput)).perform(replaceText("still-here"), closeSoftKeyboard())

            scenario.onActivity { activity ->
                instrumentation.callActivityOnNewIntent(
                    activity,
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "just text")
                    },
                )
            }

            waitUntilSaveEnabled(scenario)
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.nameInput)
                assertTrue("text mode must allow renaming", input.isEnabled)
                assertTrue(
                    "default text name should be YYYYMMDD_hhmmss, was ${input.text}",
                    input.text.toString().matches(Regex("\\d{8}_\\d{6}")),
                )
                assertTrue(activity.findViewById<Button>(R.id.sendButton).isEnabled)
                assertTrue(activity.findViewById<Button>(R.id.saveButton).isEnabled)
                val oldName = activity.findViewById<TextView>(R.id.oldNameText).text.toString()
                assertTrue("shared text preview missing", oldName.contains("just text"))
            }

            scenario.onActivity { it.intent = originalIntent }
        }
    }

    @Test
    fun textShare_restoresEditedNameAfterRecreate() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "just text")
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntilSaveEnabled(scenario)
            onView(withId(R.id.nameInput)).perform(replaceText("text-name"), closeSoftKeyboard())

            scenario.recreate()

            onView(withId(R.id.nameInput)).check(matches(withText("text-name")))
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
            onView(withId(R.id.saveButton)).check(matches(isEnabled()))
        }
    }

    @Test
    fun viewIntent_copiesFileLikeShare() {
        val source = newSource("opened-${System.nanoTime()}.txt", "opened payload")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uriFor(source), "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntilCopied(scenario)
            onView(withId(R.id.oldNameText)).check(matches(withText(source.name)))
            onView(withId(R.id.nameInput)).check(matches(isEnabled()))
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
            onView(withId(R.id.saveButton)).check(matches(isEnabled()))
        }
    }

    @Test
    fun viewIntent_withoutType_stillOpens() {
        val source = newSource("untyped-${System.nanoTime()}.bin", "untyped payload")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uriFor(source)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntilCopied(scenario)
            onView(withId(R.id.oldNameText)).check(matches(withText(source.name)))
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
        }
    }

    @Test
    fun viewIntent_ofFolder_fallsBackToPlainText() {
        val authority = "${context.packageName}.testfolder"
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(tree, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntilSaveEnabled(scenario)
            // 和文件夹分享一样：不展开，按纯文本处理这个 URI。
            onView(withId(R.id.nameInput)).check(matches(isEnabled()))
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
            scenario.onActivity { activity ->
                val preview = activity.findViewById<TextView>(R.id.oldNameText).text.toString()
                assertTrue("folder uri should be shown as text: $preview", preview.contains(tree.toString()))
            }
        }
    }

    /**
     * 直接问 PackageManager：beta 分支的 VIEW filter（SaveCopy 形状：7 类 MIME + 显式 content scheme）
     * 真的能被隐式请求解析到；https 和无 MIME 的 content 请求都解析不到。
     */
    @Test
    fun viewIntentFilter_resolvesContentUris_andIgnoresWebLinks() {
        val pm = context.packageManager
        val types = listOf(
            "application/pdf",
            "audio/mpeg",
            "font/ttf",
            "image/png",
            "model/gltf-binary",
            "text/plain",
            "video/mp4",
        )
        for (type in types) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://moe.hellorename.probe/file"), type)
            }
            val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(
                "ACTION_VIEW ($type) 应解析到本应用，实际: $resolved",
                resolved.any { it.activityInfo.packageName == context.packageName },
            )
        }

        // 对照组有意删掉了“只声明 content scheme”的兜底：没有 MIME 的打开请求不会出现本应用。
        val untyped = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://moe.hellorename.probe/file.bin")
        }
        val untypedResolved = pm.queryIntentActivities(untyped, PackageManager.MATCH_DEFAULT_ONLY)
        assertFalse(
            "无 MIME 的 VIEW 不应解析到本应用（beta 分支的已知代价），实际: $untypedResolved",
            untypedResolved.any { it.activityInfo.packageName == context.packageName },
        )

        val web = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("https://example.com/a") }
        val webResolved = pm.queryIntentActivities(web, PackageManager.MATCH_DEFAULT_ONLY)
        assertFalse(
            "http(s) 链接不应解析到本应用（VIEW 只声明 content scheme），实际: $webResolved",
            webResolved.any { it.activityInfo.packageName == context.packageName },
        )
    }

    /**
     * 文件入口必须挂在 ShareActivity（无界面转接组件）上，MainActivity 只保留桌面入口。
     * HyperOS 的分享面板只展示“专用文件处理组件”，入口挂在桌面 Activity 上会被过滤掉。
     */
    @Test
    fun fileEntries_resolveToShareActivity() {
        val pm = context.packageManager
        val entries = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://moe.hellorename.probe/file.zip"), "application/zip")
            },
            Intent(Intent.ACTION_SEND).apply { type = "application/zip" },
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/png" },
        )
        for (intent in entries) {
            val names = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.name }
            assertTrue(
                "$intent 应解析到 ShareActivity，实际: $names",
                "moe.hellorename.ShareActivity" in names,
            )
            assertFalse(
                "$intent 不应再解析到 MainActivity，实际: $names",
                "moe.hellorename.MainActivity" in names,
            )
        }
    }

    /** ShareActivity 只做转接：收到的 VIEW 请求必须原样到达 MainActivity 并完成复制。 */
    @Test
    fun shareActivity_forwardsToMainActivity() {
        val source = newSource("forwarded-${System.nanoTime()}.txt", "forwarded payload")
        val intent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uriFor(source), "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        instrumentation.startActivitySync(intent)

        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            val resumed = resumedMainActivity()
            var ready = false
            if (resumed != null) {
                instrumentation.runOnMainSync {
                    ready = resumed.findViewById<Button>(R.id.sendButton).isEnabled
                }
            }
            if (resumed != null && ready) {
                onView(withId(R.id.oldNameText)).check(matches(withText(source.name)))
                instrumentation.runOnMainSync { resumed.finish() }
                val goneDeadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < goneDeadline && resumedMainActivity() != null) {
                    Thread.sleep(50L)
                }
                return
            }
            Thread.sleep(100L)
        }
        throw AssertionError("ShareActivity 没有在 20s 内把请求转交给 MainActivity")
    }

    /**
     * SEND / SEND_MULTIPLE 的 filter 不能带 scheme：分享请求一般没有 data URI，
     * 一旦写成 content scheme，scheme 为 null 的 Intent 会直接判 NO_MATCH_DATA。
     */
    @Test
    fun sendIntentFilters_resolveTypedIntentsWithoutData() {
        val pm = context.packageManager
        val sends = listOf(
            Intent(Intent.ACTION_SEND).apply { type = "text/plain" },
            Intent(Intent.ACTION_SEND).apply { type = "*/*" },
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/*" },
        )
        for (intent in sends) {
            val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(
                "$intent 应解析到本应用，实际: $resolved",
                resolved.any { it.activityInfo.packageName == context.packageName },
            )
        }
    }

    @Test
    fun multiShare_disablesRenameAndSend_andSavesAllFiles() {
        val first = newSource("multi-first-${System.nanoTime()}.png", "first payload")
        val second = newSource("multi-second-${System.nanoTime()}.png", "second payload")
        val names = mapOf(first.name to "first payload", second.name to "second payload")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "image/png"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uriFor(first), uriFor(second)))
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntilSaveEnabled(scenario)
            onView(withId(R.id.nameInput)).check(matches(not(isEnabled())))
            onView(withId(R.id.sendButton)).check(matches(not(isEnabled())))
            onView(withId(R.id.saveButton)).check(matches(isEnabled()))
            scenario.onActivity { activity ->
                val listed = activity.findViewById<TextView>(R.id.oldNameText).text.toString()
                assertTrue("first file missing from list: $listed", listed.contains(first.name))
                assertTrue("second file missing from list: $listed", listed.contains(second.name))
            }

            scenario.recreate()
            onView(withId(R.id.nameInput)).check(matches(not(isEnabled())))
            onView(withId(R.id.sendButton)).check(matches(not(isEnabled())))
            onView(withId(R.id.saveButton)).check(matches(isEnabled()))

            onView(withId(R.id.saveButton)).perform(click())
            val saved = awaitSavedFiles(names)
            try {
                saved.forEach { (name, uri) ->
                    val actual = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(StandardCharsets.UTF_8)
                    }
                    assertEquals(names[name], actual)
                }
            } finally {
                saved.values.forEach { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
        }
    }

    @Test
    fun folderShare_isTreatedAsPlainText() {
        val authority = "${context.packageName}.testfolder"
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = DocumentsContract.Document.MIME_TYPE_DIR
            putExtra(Intent.EXTRA_STREAM, tree)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntilSaveEnabled(scenario)
            // 文件夹按纯文本处理：可改名、可保存、可原样转发。
            onView(withId(R.id.nameInput)).check(matches(isEnabled()))
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
            scenario.onActivity { activity ->
                val preview = activity.findViewById<TextView>(R.id.oldNameText).text.toString()
                assertTrue("folder uri should be shown as text: $preview", preview.contains(tree.toString()))
                val hint = activity.findViewById<TextView>(R.id.hintText).text.toString()
                assertTrue("hint should explain plain-text fallback: $hint", hint.contains("plain text"))
            }

            scenario.recreate()
            onView(withId(R.id.nameInput)).check(matches(isEnabled()))
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
            scenario.onActivity { activity ->
                val preview = activity.findViewById<TextView>(R.id.oldNameText).text.toString()
                assertTrue("folder uri lost after recreate: $preview", preview.contains(tree.toString()))
            }

            var displayName = ""
            scenario.onActivity { activity ->
                displayName = activity.findViewById<EditText>(R.id.nameInput).text.toString() + ".txt"
            }
            onView(withId(R.id.saveButton)).perform(scrollTo(), click())
            val saved = awaitSavedFiles(mapOf(displayName to tree.toString()))
            try {
                val uri = saved.getValue(displayName)
                val actual = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(StandardCharsets.UTF_8)
                }
                assertEquals(tree.toString(), actual)
            } finally {
                saved.values.forEach { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
        }
    }

    @Test
    fun textShare_savesSharedTextAsTxt() {
        val unique = "text-${System.nanoTime()}"
        // 保留 CRLF / CR / LF 原样，且不能有 UTF-8 BOM。
        val body = "line1\r\nline2\rline3\nline4"
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntilSaveEnabled(scenario)
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
            onView(withId(R.id.nameInput)).perform(replaceText(unique), closeSoftKeyboard())
            onView(withId(R.id.saveButton)).perform(click())

            val name = "$unique.txt"
            val saved = awaitSavedFiles(mapOf(name to body))
            try {
                val uri = saved.getValue(name)
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes()
                } ?: throw AssertionError("saved text missing")
                assertEquals(body, bytes.toString(StandardCharsets.UTF_8))
                assertTrue(
                    "text file must not start with a UTF-8 BOM",
                    !(bytes.size >= 3 &&
                        bytes[0] == 0xEF.toByte() &&
                        bytes[1] == 0xBB.toByte() &&
                        bytes[2] == 0xBF.toByte()),
                )
            } finally {
                saved.values.forEach { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
        }
    }

    @Test
    fun linkShare_savesUrlAsText() {
        val unique = "link-${System.nanoTime()}"
        val url = "https://example.com/${System.nanoTime()}"
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntilSaveEnabled(scenario)
            scenario.onActivity { activity ->
                val oldName = activity.findViewById<TextView>(R.id.oldNameText).text.toString()
                assertTrue("link preview missing", oldName.contains(url))
            }
            onView(withId(R.id.nameInput)).perform(replaceText(unique), closeSoftKeyboard())
            onView(withId(R.id.saveButton)).perform(click())

            val name = "$unique.txt"
            val saved = awaitSavedFiles(mapOf(name to url))
            try {
                val uri = saved.getValue(name)
                val actual = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(StandardCharsets.UTF_8)
                }
                assertEquals(url, actual)
            } finally {
                saved.values.forEach { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
        }
    }

    @Test
    fun unknownShare_fallsBackToRawTxt_andKeepsSendEnabled() {
        val unique = "unknown-${System.nanoTime()}"
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "application/x-hellorename-unknown"
            putExtra("mystery", "value-${System.nanoTime()}")
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntilSaveEnabled(scenario)
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.nameInput)
                assertTrue(
                    "unknown fallback should get a timestamp name, was ${input.text}",
                    input.text.toString().matches(Regex("\\d{8}_\\d{6}")),
                )
                val preview = activity.findViewById<TextView>(R.id.oldNameText).text.toString()
                assertTrue("raw intent dump missing", preview.contains("Unrecognized share request"))
                assertTrue("unknown MIME missing from dump", preview.contains("application/x-hellorename-unknown"))
            }

            onView(withId(R.id.nameInput)).perform(replaceText(unique), closeSoftKeyboard())
            onView(withId(R.id.saveButton)).perform(scrollTo(), click())

            val name = "$unique.txt"
            val saved = awaitSavedFiles(mapOf(name to ""))
            try {
                val uri = saved.getValue(name)
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(StandardCharsets.UTF_8)
                } ?: throw AssertionError("saved raw text missing")
                assertTrue("raw action missing", text.contains("action: android.intent.action.SEND"))
                assertTrue("raw MIME missing", text.contains("application/x-hellorename-unknown"))
                assertTrue("raw extra missing", text.contains("mystery"))
            } finally {
                saved.values.forEach { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
        }
    }

    @Test
    fun clearCacheButton_keepsCurrentSession_andDeletesOtherSessions() {
        val decoy = File(context.cacheDir, CacheStore.SHARED_DIR + "/decoy-session")
        assertTrue(decoy.mkdirs() || decoy.isDirectory)
        File(decoy, "old.bin").writeText("old payload")
        val stateRoot = File(context.cacheDir, CacheStore.STATE_DIR)
        assertTrue(stateRoot.mkdirs() || stateRoot.isDirectory)
        // 交付时刻设成“刚刚”，冷启动的自动清理会保留它；只有手动“清空缓存”才会删
        File(stateRoot, "decoy-session").writeText(System.currentTimeMillis().toString())

        val source = newSource()
        ActivityScenario.launch<MainActivity>(sendIntent(uriFor(source))).use { scenario ->
            waitUntilCopied(scenario)
            onView(withId(R.id.clearCacheButton)).check(matches(isDisplayed()))
            onView(withId(R.id.clearCacheButton)).perform(click())
            onView(withText(R.string.clear_cache_confirm)).perform(click())

            val deadline = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < deadline && decoy.exists()) {
                Thread.sleep(100L)
            }
            assertFalse("其它会话应被强制清掉", decoy.exists())
            assertFalse("被删会话的交付标记也要清掉", File(stateRoot, "decoy-session").exists())
            val sessions = File(context.cacheDir, CacheStore.SHARED_DIR)
                .listFiles()
                ?.filter { it.isDirectory }
                .orEmpty()
            assertEquals("当前正在使用的会话必须保留", 1, sessions.size)
            onView(withId(R.id.oldNameText)).check(matches(withText(source.name)))
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
        }
    }

    @Test
    fun checksums_areShown_andSurviveRecreate() {
        val source = newSource()
        val originalIntent = sendIntent(uriFor(source))
        ActivityScenario.launch<MainActivity>(originalIntent).use { scenario ->
            waitUntilCopied(scenario)
            assertChecksums(scenario)

            scenario.recreate()

            assertChecksums(scenario)
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
        }
    }

    /** 轮询当前 RESUMED 的 MainActivity（ShareActivity 转交后界面由它承担）。 */
    private fun resumedMainActivity(): MainActivity? {
        var found: MainActivity? = null
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
        }
        return found
    }

    private fun assertChecksums(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            assertEquals(
                "checksum section should be visible after copy",
                View.VISIBLE,
                activity.findViewById<View>(R.id.checksumSection).visibility,
            )
            val md5 = activity.findViewById<TextView>(R.id.checksumMd5).text.toString()
            val sha1 = activity.findViewById<TextView>(R.id.checksumSha1).text.toString()
            val sha256 = activity.findViewById<TextView>(R.id.checksumSha256).text.toString()
            assertTrue(md5, md5.contains("00184e6135af969e09086d0058d53385"))
            assertTrue(sha1, sha1.contains("325955adedc149e2f90962bb2b30374b6973023b"))
            assertTrue(
                sha256,
                sha256.contains(
                    "854f0a004368659f7ac3df08e942c3e5" +
                        "d0a31ad534d2deb8f602f11ecd2c302f",
                ),
            )
        }
    }

    private fun newSource(
        name: String = "source-${System.nanoTime()}.png",
        content: String = "hello from instrumentation",
    ): File {
        val dir = File(context.cacheDir, CacheStore.SHARED_DIR)
        dir.mkdirs()
        val file = File(dir, name)
        file.writeText(content)
        return file
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun sendIntent(uri: Uri): Intent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_SEND
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
    }

    private fun waitUntilCopied(scenario: ActivityScenario<MainActivity>) {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            var enabled = false
            scenario.onActivity { activity ->
                enabled = activity.findViewById<Button>(R.id.sendButton).isEnabled
            }
            if (enabled) return
            Thread.sleep(100L)
        }
        throw AssertionError("copy did not finish within 20s")
    }

    private fun waitUntilSaveEnabled(scenario: ActivityScenario<MainActivity>) {
        val deadline = System.currentTimeMillis() + 20_000L
        var status = ""
        while (System.currentTimeMillis() < deadline) {
            var enabled = false
            scenario.onActivity { activity ->
                enabled = activity.findViewById<Button>(R.id.saveButton).isEnabled
                status = activity.findViewById<TextView>(R.id.statusText).text.toString()
            }
            if (enabled) return
            Thread.sleep(100L)
        }
        throw AssertionError("save did not become available within 20s; status=$status")
    }

    /** 轮询 MediaStore，直到所有预期 DISPLAY_NAME 都出现；返回 name -> content Uri */
    private fun awaitSavedFiles(expected: Map<String, String>): Map<String, Uri> {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            val found = mutableMapOf<String, Uri>()
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            for (name in expected.keys) {
                try {
                    context.contentResolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        projection,
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                        arrayOf(name),
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            found[name] = ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                cursor.getLong(0),
                            )
                        }
                    }
                } catch (_: Throwable) {
                    // 查询偶发失败：下一轮再试
                }
            }
            if (found.size == expected.size) return found
            Thread.sleep(100L)
        }
        throw AssertionError("saved files not found in Downloads: ${expected.keys}")
    }
}
