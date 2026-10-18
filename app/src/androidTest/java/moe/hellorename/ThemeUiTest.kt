package moe.hellorename

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 云端模拟器上的界面门禁（v1.3.1 起由 .github/workflows/emulator-ui.yml 执行）。
 *
 * 这些断言直接对应 v1.3.0 线上发现的 bug：
 *  - 主题若仍带系统 ActionBar，页面顶部会出现两个标题；这里要求 supportActionBar 为 null，
 *    并且用 Espresso 的 withText(appName) 反证同名标题只有一个（多个会抛 AmbiguousViewMatcherException）。
 *  - 顶栏必须固定 56dp，且紧贴内容区顶部。
 *  - 浅色模式白底黑字、深色模式黑底白字，只允许这两套默认配色。
 */
@RunWith(AndroidJUnit4::class)
class ThemeUiTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appName: String
        get() = instrumentation.targetContext.getString(R.string.app_name)

    @Before
    fun followSystemBeforeTest() {
        setNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @After
    fun followSystemAfterTest() {
        setNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test
    fun lightMode_hasSingleTopBar_andWhiteBackgroundBlackText() {
        setNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertSingleTopBarTitle(scenario)
            assertTopBar(scenario, expectedBackground = Color.WHITE, expectedText = Color.BLACK)
        }
    }

    @Test
    fun darkMode_hasSingleTopBar_andBlackBackgroundWhiteText() {
        setNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertSingleTopBarTitle(scenario)
            assertTopBar(scenario, expectedBackground = Color.BLACK, expectedText = Color.WHITE)
        }
    }

    private fun assertSingleTopBarTitle(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            assertNull(
                "系统 ActionBar 必须关闭：主题应继承 NoActionBar，否则会和自绘 Toolbar 出现两个标题",
                activity.supportActionBar,
            )
            val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            assertEquals(appName, toolbar.title?.toString())
            if (Build.VERSION.SDK_INT >= 35) {
                // targetSdk 36 在 Android 15+ 强制 edge-to-edge：状态栏区域由根布局 padding 让出，
                // Toolbar 自身仍是 56dp，但 top 等于根布局的 paddingTop，而不是 0。
                val root = activity.findViewById<View>(R.id.root)
                assertTrue("edge-to-edge 下根布局必须让出状态栏", root.paddingTop > 0)
                assertEquals(
                    "Toolbar 应紧贴根布局的内容区顶部",
                    root.paddingTop,
                    toolbar.top,
                )
            } else {
                assertEquals(
                    "Toolbar 必须位于内容区顶部，上方不能再有系统标题栏",
                    0,
                    toolbar.top,
                )
            }
        }
        // 若还存在第二个同名标题（例如系统 ActionBar），这里会匹配到多个 View 而失败
        onView(withText(appName)).check(matches(isDisplayed()))
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
    }

    @Suppress("DEPRECATION")
    private fun assertTopBar(
        scenario: ActivityScenario<MainActivity>,
        expectedBackground: Int,
        expectedText: Int,
    ) {
        if (Build.VERSION.SDK_INT < 35) {
            scenario.onActivity { activity ->
                assertEquals(
                    "状态栏颜色必须与顶栏一致",
                    expectedBackground,
                    activity.window.statusBarColor,
                )
            }
        }
        val density = instrumentation.targetContext.resources.displayMetrics.density
        onView(withId(R.id.toolbar)).check { view, _ ->
            assertEquals("顶栏高度必须是 56dp", (56 * density).toInt(), view.height)
            val background = view.background
            assertTrue("顶栏背景必须是纯色", background is ColorDrawable)
            assertEquals(expectedBackground, (background as ColorDrawable).color)
        }
        onView(withText(appName)).check { view, _ ->
            assertEquals(expectedText, (view as TextView).currentTextColor)
        }
    }

    private fun setNightMode(mode: Int) {
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
        instrumentation.waitForIdleSync()
    }
}
