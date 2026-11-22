package ru.privatediary

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NoInternetPermissionTest {
    @Test fun mergedManifestEnforcesDistributionPrivacy() {
        val file = File(requireNotNull(System.getProperty("mergedManifestPath")))
        assertTrue("Merged manifest must exist", file.isFile)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val store = System.getProperty("distribution") == "store"
        val permissions = mutableListOf<String>()
        for (tag in listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")) {
            val nodes = document.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as org.w3c.dom.Element
                permissions += node.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
            }
        }
        assertEquals(store, "android.permission.INTERNET" in permissions)
        if (store) {
            val ns = "http://schemas.android.com/apk/res/android"
            for (tag in listOf("activity", "service", "provider", "receiver")) {
                val nodes = document.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as org.w3c.dom.Element
                    val name = node.getAttributeNS(ns, "name")
                    if (name.startsWith("com.yandex.") || name.startsWith("io.appmetrica.")) {
                        assertEquals(name, ":ads", node.getAttributeNS(ns, "process"))
                        assertEquals(name, "false", node.getAttributeNS(ns, "exported"))
                        if (tag == "provider") assertEquals(name, "false", node.getAttributeNS(ns, "enabled"))
                    }
                }
            }
        }
    }
}
