package ru.privatediary.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.privatediary.debug.StorePreviewActivity
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit opt-in capture, separate from the database/search regression suite. */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun captureSyntheticStoreScreens() {
        assumeTrue("Run with -e captureStoreScreenshots true to generate store pictures",
            InstrumentationRegistry.getArguments().getString("captureStoreScreenshots") == "true")
        val previousLocale = Locale.getDefault()
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val directory = File(instrumentation.targetContext.filesDir, "store-previews").apply { mkdirs() }
        try {
            for (language in listOf("ru", "en")) {
                StorePreviewActivity.languageTag = language
                for (scene in listOf("journal", "editor", "search", "calendar")) {
                    val intent = Intent(instrumentation.targetContext, StorePreviewActivity::class.java)
                        .setAction(StorePreviewActivity.ACTION_CAPTURE)
                        .putExtra(StorePreviewActivity.EXTRA_SCENE, scene)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val activity = instrumentation.startActivitySync(intent) as StorePreviewActivity
                    try {
                        awaitFrames(activity)
                        if (scene == "search") {
                            clickText(if (language == "ru") "Поиск" else "Search", preferBottom = true)
                            awaitFrames(activity)
                        }
                        if (scene == "calendar") {
                            clickText(if (language == "ru") "Календарь" else "Calendar")
                            awaitFrames(activity)
                            // Move the invitation out of view so all six calendar weeks fit.
                            swipeUp(activity.window.decorView.width / 2f,
                                activity.window.decorView.height * 0.74f,
                                activity.window.decorView.height * 0.54f)
                            awaitFrames(activity)
                        }
                        val target = File(directory, "$language-$scene.png")
                        capture(activity, target)
                        assertTrue("Missing PNG for $language/$scene", target.length() > 10_000L)
                    } finally {
                        instrumentation.runOnMainSync { activity.finish() }
                        instrumentation.waitForIdleSync()
                    }
                }
            }
            assertEquals(8, directory.listFiles { f -> f.extension == "png" }?.size)
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousZone)
            StorePreviewActivity.languageTag = "ru"
        }
    }

    private fun awaitFrames(activity: StorePreviewActivity) {
        instrumentation.waitForIdleSync()
        val rendered = CountDownLatch(1)
        instrumentation.runOnMainSync {
            activity.window.decorView.postOnAnimation {
                activity.window.decorView.postOnAnimation { rendered.countDown() }
            }
        }
        assertTrue("Preview did not reach a rendered frame", rendered.await(10, TimeUnit.SECONDS))
        // Wait for the actual production AnimatedVisibility transitions, not a mocked layout.
        SystemClock.sleep(450)
        instrumentation.waitForIdleSync()
        assertTrue("Preview did not draw", activity.drawnFrames.get() > 0L)
    }

    private fun clickText(text: String, preferBottom: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                val matches = root.findAccessibilityNodeInfosByText(text)
                    .filter { node ->
                        node.text?.toString()?.lineSequence()?.any { it.trim() == text } == true ||
                            node.contentDescription?.toString()?.lineSequence()?.any { it.trim() == text } == true
                    }
                    .sortedBy { node -> Rect().also(node::getBoundsInScreen).centerY() }
                val node = if (preferBottom) matches.lastOrNull() else matches.firstOrNull()
                if (node != null && clickNodeOrParent(node)) return
            }
            SystemClock.sleep(100)
        }
        fail("The genuine UI did not expose a clickable '$text' item")
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val target = current ?: return false
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = target.parent
        }
        return false
    }

    private fun swipeUp(x: Float, fromY: Float, toY: Float) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float, at: Long) {
            val motion = MotionEvent.obtain(down, at, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(motion, true)) }
            finally { motion.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, fromY, down)
        for (step in 1..12) {
            SystemClock.sleep(16)
            event(MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * step / 12f, SystemClock.uptimeMillis())
        }
        event(MotionEvent.ACTION_UP, toY, SystemClock.uptimeMillis())
    }

    private fun capture(activity: StorePreviewActivity, target: File) {
        val width = activity.window.decorView.width
        val height = activity.window.decorView.height
        assertTrue("Store pictures need at least 1080×1920 pixels: ${width}×$height", width >= 1080 && height >= 1920)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var status = PixelCopy.ERROR_UNKNOWN
        instrumentation.runOnMainSync {
            PixelCopy.request(activity.window, bitmap, { result -> status = result; copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("PixelCopy did not return", copied.await(10, TimeUnit.SECONDS))
        assertEquals("Could not copy the fixture window", PixelCopy.SUCCESS, status)
        // Detect blank/blocked windows instead of shipping a misleading store screenshot.
        val colors = HashSet<Int>()
        for (y in height / 8 until height * 7 / 8 step 40) {
            for (x in width / 8 until width * 7 / 8 step 40) colors += bitmap.getPixel(x, y)
        }
        assertTrue("The captured UI appears blank", colors.size > 16)
        assertFalse("Secure-window black image is not a store preview", colors.all { Color.red(it) < 8 && Color.green(it) < 8 && Color.blue(it) < 8 })
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
