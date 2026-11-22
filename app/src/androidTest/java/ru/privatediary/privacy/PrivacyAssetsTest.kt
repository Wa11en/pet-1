package ru.privatediary.privacy

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.privatediary.BuildConfig
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PrivacyAssetsTest {
    @Test fun theInstalledAppContainsBothPolicyLanguagesAndThePublishedContact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val policy = context.assets.open("privacy/policy.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(1, policy.getInt("schema_version"))
        assertNotNull(LocalDate.parse(policy.getString("updated_at")))
        assertEquals(BuildConfig.SUPPORT_EMAIL, policy.getJSONObject("identity").getString("support_email"))
        for (language in listOf("ru", "en")) {
            val document = policy.getJSONObject("locales").getJSONObject(language)
            assertTrue(document.getString("summary").isNotBlank())
            val sections = document.getJSONArray("sections")
            assertTrue(sections.length() >= 8)
            val ids = HashSet<String>()
            for (index in 0 until sections.length()) {
                val section = sections.getJSONObject(index)
                assertTrue(ids.add(section.getString("id")))
                assertTrue(section.getString("title").isNotBlank())
                assertTrue(section.getJSONArray("paragraphs").length() > 0)
            }
        }
    }

    @Test fun installedNetworkPermissionMatchesTheDistributionAndPreviewComponentsStayPrivate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val permissions = manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertEquals(BuildConfig.NETWORK_FEATURES, permissions.contains("android.permission.INTERNET"))
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val preview = ComponentName(context.packageName, "ru.privatediary.debug.StorePreviewActivity")
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            assertThrows(PackageManager.NameNotFoundException::class.java) { manager.getActivityInfo(preview, 0) }
        } else {
            assertFalse(manager.getActivityInfo(preview, 0).exported)
        }
    }

    @Test fun storeAdsAreConfinedToTheirProcessAndAutomaticProvidersAreDisabled() {
        if (!BuildConfig.NETWORK_FEATURES) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val disabled = PackageManager.MATCH_DISABLED_COMPONENTS
        val expectedProcess = "${context.packageName}:ads"
        fun component(name: String) = ComponentName(context.packageName, name)
        for (name in listOf("ru.privatediary.monetization.ads.StoreAdActivity", "com.yandex.mobile.ads.common.AdActivity")) {
            val info = manager.getActivityInfo(component(name), disabled)
            assertEquals(expectedProcess, info.processName)
            assertFalse(info.exported)
            assertTrue(info.enabled)
        }
        for (name in listOf("com.yandex.mobile.ads.core.initializer.YandexAdsInitializeProvider",
            "io.appmetrica.analytics.internal.PreloadInfoContentProvider",
            "com.yandex.mobile.ads.features.debugpanel.data.local.DebugPanelFileProvider")) {
            val info = manager.getProviderInfo(component(name), disabled)
            assertEquals(expectedProcess, info.processName)
            assertFalse(info.enabled)
            assertFalse(info.exported)
        }
        for (name in listOf("com.yandex.mobile.ads.features.debugpanel.ui.IntegrationInspectorActivity",
            "com.google.android.gms.common.api.GoogleApiActivity")) {
            val info = manager.getActivityInfo(component(name), disabled)
            assertEquals(expectedProcess, info.processName)
            assertFalse(info.enabled)
            assertFalse(info.exported)
        }
        val service = manager.getServiceInfo(component("io.appmetrica.analytics.internal.AppMetricaService"), disabled)
        assertEquals(expectedProcess, service.processName)
        assertFalse(service.exported)
        // Runtime-scoped SDK classes are not on lint's compile classpath.
        Class.forName(service.name, false, context.classLoader)
        Class.forName("com.google.android.gms.common.api.GoogleApiActivity", false, context.classLoader)
        val metadata = manager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        assertFalse(metadata.getBoolean("com.yandex.mobile.ads.AUTOMATIC_SDK_INITIALIZATION", true))
        val permissions = manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("com.google.android.gms.permission.AD_ID"))
        val policy = context.assets.open("privacy/policy.json").bufferedReader().use { JSONObject(it.readText()) }
        assertTrue(policy.getString("policy_version").startsWith("store-"))
        val sections = policy.getJSONObject("locales").getJSONObject("ru").getJSONArray("sections")
        val ids = (0 until sections.length()).map { sections.getJSONObject(it).getString("id") }
        assertTrue(ids.containsAll(listOf("advertising", "payments")))
    }
}
