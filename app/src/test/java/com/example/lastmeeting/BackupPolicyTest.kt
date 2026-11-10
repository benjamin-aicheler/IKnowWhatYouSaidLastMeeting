package com.example.lastmeeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Configuration regression checks; real OEM device-transfer behavior still needs device testing. */
class BackupPolicyTest {

    private val androidNamespace = "http://schemas.android.com/apk/res/android"
    private val application = parse(File("src/main/AndroidManifest.xml"))
        .getElementsByTagName("application").item(0) as Element

    @Test
    fun cloudBackupRemainsDisabled() {
        assertEquals("false", application.getAttributeNS(androidNamespace, "allowBackup"))
    }

    @Test
    fun android12RulesExcludeEveryStorageDomainForBothTransferModes() {
        val rules = referencedRules("dataExtractionRules")
        assertEquals("data-extraction-rules", rules.tagName)

        for (mode in listOf("cloud-backup", "device-transfer")) {
            val sections = rules.getElementsByTagName(mode)
            assertEquals("Exactly one $mode policy must be declared", 1, sections.length)
            assertAllStorageExcluded(sections.item(0) as Element)
        }
    }

    @Test
    fun legacyRulesExcludeEveryStorageDomain() {
        val rules = referencedRules("fullBackupContent")
        assertEquals("full-backup-content", rules.tagName)
        assertAllStorageExcluded(rules)
    }

    private fun referencedRules(attribute: String): Element {
        val reference = application.getAttributeNS(androidNamespace, attribute)
        assertTrue("$attribute must reference an XML resource", reference.startsWith("@xml/"))
        return parse(File("src/main/res/xml/${reference.removePrefix("@xml/")}.xml"))
    }

    private fun assertAllStorageExcluded(policy: Element) {
        val excludes = policy.getElementsByTagName("exclude")
        val excludedDomains = (0 until excludes.length).map { excludes.item(it) as Element }
            .filter { it.getAttribute("path") in listOf(".", "./") }
            .map { it.getAttribute("domain") }
            .toSet()

        // Root is traversed separately from files (recordings/DataStore) and databases (Room).
        // A root-only rule must fail even though those directories are physically beneath root.
        val storageDomains = listOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref"
        )
        for (domain in storageDomains) {
            assertTrue("${policy.tagName} must exclude all of $domain", domain in excludedDomains)
        }
    }

    private fun parse(file: File): Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file).documentElement
}
