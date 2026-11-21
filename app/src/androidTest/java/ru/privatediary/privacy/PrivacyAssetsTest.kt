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

    @Test fun theInstalledReleaseExcludesPreviewComponentsAndInternet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val permissions = manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val preview = ComponentName(context.packageName, "ru.privatediary.debug.StorePreviewActivity")
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            assertThrows(PackageManager.NameNotFoundException::class.java) { manager.getActivityInfo(preview, 0) }
        } else {
            assertFalse(manager.getActivityInfo(preview, 0).exported)
        }
    }
}
