package moe.hellorename

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.assertEquals
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
    fun malformedExtraStream_doesNotCrash() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, Bundle().apply { putString("not", "a uri") })
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            onView(withId(R.id.oldNameText)).check(matches(withText(R.string.old_name_none)))
            onView(withId(R.id.sendButton)).check(matches(not(isEnabled())))
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
                    input.text.toString().matches(Regex("\d{8}_\d{6}")),
                )
                val preview = activity.findViewById<TextView>(R.id.oldNameText).text.toString()
                assertTrue("raw intent dump missing", preview.contains("Unrecognized share request"))
                assertTrue("unknown MIME missing from dump", preview.contains("application/x-hellorename-unknown"))
            }

            onView(withId(R.id.nameInput)).perform(replaceText(unique), closeSoftKeyboard())
            onView(withId(R.id.saveButton)).perform(click())

            val name = "$unique.txt"
            val saved = awaitSavedFiles(mapOf(name to ""))
            try {
                val uri = saved.getValue(name)
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(StandardCharsets.UTF_8)
                }
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
        while (System.currentTimeMillis() < deadline) {
            var enabled = false
            scenario.onActivity { activity ->
                enabled = activity.findViewById<Button>(R.id.saveButton).isEnabled
            }
            if (enabled) return
            Thread.sleep(100L)
        }
        throw AssertionError("save did not become available within 20s")
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
