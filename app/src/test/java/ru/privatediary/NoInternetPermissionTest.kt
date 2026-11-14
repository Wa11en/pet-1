package ru.privatediary

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NoInternetPermissionTest {
    @Test fun mergedManifestHasNoInternetPermission() {
        val file = File(requireNotNull(System.getProperty("mergedManifestPath")))
        assertTrue("Merged manifest must exist", file.isFile)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        for (tag in listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")) {
            val nodes = document.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as org.w3c.dom.Element
                assertNotEquals("android.permission.INTERNET", node.getAttributeNS("http://schemas.android.com/apk/res/android", "name"))
            }
        }
    }
}
