package com.example.lastmeeting.util

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lastmeeting.R
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run on API 28 and API 29+ to cover both real Downloads destinations. */
@RunWith(AndroidJUnit4::class)
class DownloadsExportTest {
    private lateinit var context: Context
    private val prefix = "Export72_${System.nanoTime()}_"
    private val title = "${prefix}Müller"
    private val inputs = mutableListOf<File>()

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    @After
    fun tearDown() {
        exports().forEach { exported ->
            if (exported.uri.scheme == "file") File(requireNotNull(exported.uri.path)).delete()
            else context.contentResolver.delete(exported.uri, null, null)
        }
        inputs.forEach { it.delete() }
    }

    @Test
    fun duplicateAudioExportsPreserveBothCopiesAndTheSource() {
        val audio = inputFile().apply { writeText("Earlier recording") }
        val firstPath = PdfExporter.exportAudioToDownloads(context, title, audio).getOrThrow()
        val first = exports().single()
        val earlierBytes = read(first)
        assertEquals(first.name, firstPath.substringAfterLast('/'))

        audio.writeText("Later recording")
        val secondPath = PdfExporter.exportAudioToDownloads(context, title, audio).getOrThrow()
        val all = exports()
        assertEquals(2, all.size)
        val second = all.single { it.uri != first.uri }
        assertEquals(second.name, secondPath.substringAfterLast('/'))
        assertTrue(all.all { it.name.contains("Müller") && it.name.endsWith(".m4a") })
        assertArrayEquals(earlierBytes, read(first))
        assertArrayEquals(audio.readBytes(), read(second))
        assertEquals("Later recording", audio.readText())
    }

    @Test
    fun repeatedTranscriptAndProtocolPdfsRemainIndependent() {
        for (label in listOf(context.getString(R.string.label_transcript), context.getString(R.string.label_protocol))) {
            val before = exports().map { it.uri }.toSet()
            val firstPath = PdfExporter.exportToPdf(context, title, label, "Earlier meeting text").getOrThrow()
            val first = exports().single { it.uri !in before }
            val earlierBytes = read(first)
            assertEquals("%PDF-", String(earlierBytes, 0, 5, Charsets.US_ASCII))
            assertEquals(first.name, firstPath.substringAfterLast('/'))

            val secondPath = PdfExporter.exportToPdf(context, title, label, "Later meeting text").getOrThrow()
            assertArrayEquals(earlierBytes, read(first))
            assertTrue(exports().any { it.name == secondPath.substringAfterLast('/') && it.uri != first.uri })
        }
        assertEquals(4, exports().size)
        assertTrue(exports().all { it.name.contains("Müller") && it.name.endsWith(".pdf") })
    }

    @Test
    fun failedAudioReadLeavesNoNewEntryAndKeepsThePreviousDownload() {
        val audio = inputFile().apply { writeText("Earlier recording") }
        PdfExporter.exportAudioToDownloads(context, title, audio).getOrThrow()
        val first = exports().single()
        val earlierBytes = read(first)
        // A directory exists, but copying it as audio fails after a destination was created.
        val unreadableAudio = File.createTempFile("export72-directory-", "", context.cacheDir).apply {
            check(delete() && mkdir())
            inputs += this
        }

        assertTrue(PdfExporter.exportAudioToDownloads(context, title, unreadableAudio).isFailure)
        assertEquals(listOf(first), exports())
        assertArrayEquals(earlierBytes, read(first))
    }

    private fun inputFile(): File = File.createTempFile("export72-", ".m4a", context.cacheDir).also { inputs += it }

    private fun read(exported: Exported): ByteArray =
        requireNotNull(context.contentResolver.openInputStream(exported.uri)).use { it.readBytes() }

    private fun exports(): List<Exported> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return File(downloads, "LastMeeting").listFiles().orEmpty()
                .filter { it.name.startsWith(prefix) }
                .map { Exported(Uri.fromFile(it), it.name) }
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Include our pending entries too, otherwise a failed rollback could be hidden here.
        @Suppress("DEPRECATION")
        val queryUri = MediaStore.setIncludePending(collection)
        return context.contentResolver.query(queryUri,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("$prefix%"), null
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                    if (name.startsWith(prefix)) add(Exported(ContentUris.withAppendedId(collection, id), name))
                }
            }
        }.orEmpty()
    }

    private data class Exported(val uri: Uri, val name: String)
}
