package ru.privatediary.monetization.ads

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.privatediary.BuildConfig
import ru.privatediary.MainActivity

/** Runs without a publisher account, ad requests, demo blocks or any network connection. */
@RunWith(AndroidJUnit4::class)
class MissingAdConfigurationTest {
    @Test fun openingDiaryAndRequestingUnconfiguredAdsNeverStartsTheAdProcess() {
        assumeTrue("This safety case covers the default build with no placement ID", BuildConfig.YAN_AD_UNIT_ID.isBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse(AdsRuntime.isConfigured())
        assertNoAdProcess(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("An absent placement must fail before issuing an entry permit", AdsRuntime.show(activity))
            }
            instrumentation.waitForIdleSync()
            assertNoAdProcess(context)
        }
        instrumentation.waitForIdleSync()
        assertNoAdProcess(context)
    }

    private fun assertNoAdProcess(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        assertFalse("Ad SDK process started without a configured, explicitly accepted ad request",
            manager.runningAppProcesses.orEmpty().any { it.processName == "${context.packageName}:ads" })
    }
}
