package com.example.lastmeeting.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfExporterSharingTest {

    @Test
    fun firstSharedPdfRemainsUnchangedAfterAnotherSameTitleExport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = mutableListOf<File>()
        try {
            val first = PdfExporter.exportToCachePdf(
                context, "Team Sync", "Transcript", "Meeting A: approve the launch."
            ).getOrThrow().also(files::add)
            val firstUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", first
            )
            val firstContents = readUri(context, firstUri)
            assertTrue(firstContents.size > 5)
            assertEquals("%PDF-", String(firstContents, 0, 5, Charsets.US_ASCII))

            val second = PdfExporter.exportToCachePdf(
                context, "Team Sync", "Transcript", "Meeting B: cancel the launch."
            ).getOrThrow().also(files::add)
            val secondUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", second
            )

            assertNotEquals(first.canonicalPath, second.canonicalPath)
            assertNotEquals(firstUri, secondUri)
            assertArrayEquals(firstContents, readUri(context, firstUri))
            assertFalse(firstContents.contentEquals(readUri(context, secondUri)))
        } finally {
            files.forEach { it.delete() }
        }
    }

    private fun readUri(context: Context, uri: Uri): ByteArray =
        requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
}
