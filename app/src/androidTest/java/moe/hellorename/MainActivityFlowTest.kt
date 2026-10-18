package moe.hellorename

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * API 34 模拟器上的主流程 / 生命周期测试（v1.3.2 起由 QA 与 Release 的模拟器门禁执行）。
 *
 * 用 file:// URI 提供测试源文件：测试与被测应用同 UID，ContentResolver 能直接打开，
 * 不需要额外声明测试 Provider。覆盖：
 *  - 分享进入 → 复制完成；
 *  - 配置重建后输入/旧文件名/复制状态不丢（H1）；
 *  - ACTION_MAIN 重进不清空当前文件（L6）；
 *  - 异常 EXTRA_STREAM 不崩溃（L5）；
 *  - Parcelable[] 形式的 EXTRA_STREAM 仍能识别。
 */
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

        ActivityScenario.launch<MainActivity>(sendIntent(uriFor(source))).use { scenario ->
            waitUntilCopied(scenario)
            onView(withId(R.id.nameInput)).perform(replaceText("keep-me"), closeSoftKeyboard())

            scenario.onActivity { activity ->
                instrumentation.callActivityOnNewIntent(activity, Intent(Intent.ACTION_MAIN))
            }

            onView(withId(R.id.nameInput)).check(matches(withText("keep-me")))
            onView(withId(R.id.oldNameText)).check(matches(withText(displayName)))
            onView(withId(R.id.sendButton)).check(matches(isEnabled()))
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
    fun textOnlyShare_keepsExistingFile() {
        val source = newSource()
        val displayName = source.name
        ActivityScenario.launch<MainActivity>(sendIntent(uriFor(source))).use { scenario ->
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

            onView(withId(R.id.nameInput)).check(matches(withText("still-here")))
            onView(withId(R.id.oldNameText)).check(matches(withText(displayName)))
        }
    }

    private fun newSource(): File {
        val dir = File(context.cacheDir, CacheStore.SHARED_DIR)
        dir.mkdirs()
        val file = File(dir, "source-${System.nanoTime()}.png")
        file.writeText("hello from instrumentation")
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
}
