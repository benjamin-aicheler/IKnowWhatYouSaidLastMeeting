package com.example.lastmeeting.util

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.example.lastmeeting.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DownloadsExporterMediaStoreTest {
    private lateinit var app: Application
    private lateinit var provider: ExportProvider

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        provider = ExportProvider().apply {
            attachInfo(app, ProviderInfo().apply { authority = "media" })
        }
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test
    fun entryStaysPendingThroughWritingAndCloseThenReportsTheActualName() {
        val stream = recordingStream()
        val path = export(stream = stream)

        assertEquals("Downloads/LastMeeting/Müller_protocol_1.pdf", path)
        assertEquals(listOf("insert", "open", "write", "close", "publish", "query"), provider.events)
        assertEquals(1, provider.insertedValues!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("application/pdf", provider.insertedValues!!.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("${Environment.DIRECTORY_DOWNLOADS}/LastMeeting", provider.insertedValues!!.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(0, provider.pending)
        assertArrayEquals("PDF contents".toByteArray(), stream.toByteArray())
        assertTrue(provider.deletedUris.isEmpty())
    }

    @Test
    fun nullInsertionNeverOpensAStreamOrDeletesAnUnownedEntry() {
        provider.rejectInsert = true
        assertThrows(IOException::class.java) { export() }
        assertEquals(listOf("insert"), provider.events)
        assertTrue(provider.deletedUris.isEmpty())
    }

    @Test
    fun nullOutputStreamDeletesOnlyTheNewEntry() {
        assertThrows(IOException::class.java) { export(stream = null) }
        assertRollback()
        assertFalse(provider.events.contains("publish"))
    }

    @Test
    fun openFailureDeletesOnlyTheNewEntry() {
        val failure = IOException("Open failed")
        val thrown = assertThrows(IOException::class.java) {
            DownloadsExporter.writeMediaStore(app.contentResolver, "meeting.pdf", "application/pdf",
                openStream = { throw failure }) { error("Writer must not run") }
        }
        assertSame(failure, thrown)
        assertRollback()
    }

    @Test
    fun writeFailureClosesTheStreamAndRollsBackWithoutPublishing() {
        val failure = IOException("Write failed")
        val thrown = assertThrows(IOException::class.java) {
            export { stream ->
                stream.write("Partial PDF".toByteArray())
                throw failure
            }
        }
        assertSame(failure, thrown)
        assertEquals(listOf("insert", "open", "write", "close", "delete"), provider.events)
        assertRollback()
    }

    @Test
    fun closeFailureRollsBackWithoutPublishing() {
        val failure = IOException("Close failed")
        val stream = recordingStream(closeFailure = failure)
        assertSame(failure, assertThrows(IOException::class.java) { export(stream = stream) })
        assertFalse(provider.events.contains("publish"))
        assertRollback()
    }

    @Test
    fun failedPublicationRollsBackTheCompletedFile() {
        provider.publishCount = 0
        assertThrows(IOException::class.java) { export() }
        assertEquals(listOf("insert", "open", "write", "close", "publish", "delete"), provider.events)
        assertRollback()
    }

    @Test
    fun publicationExceptionRollsBackTheCompletedFile() {
        val failure = IllegalStateException("Provider failed")
        provider.publishFailure = failure
        assertSame(failure, assertThrows(IllegalStateException::class.java) { export() })
        assertRollback()
    }

    @Test
    fun missingFinalNameIsAFailureWithCleanupRatherThanAnIncorrectSuccessPath() {
        provider.returnName = false
        assertThrows(IOException::class.java) { export() }
        assertRollback()
    }

    @Test
    fun cleanupFailurePreservesTheWriteErrorAndAddsTheCleanupCause() {
        val failure = IOException("Write failed")
        val cleanup = IllegalStateException("Delete failed")
        provider.deleteFailure = cleanup
        val thrown = assertThrows(IOException::class.java) { export { throw failure } }
        assertSame(failure, thrown)
        assertSame(cleanup, thrown.suppressed.single().cause)
        assertEquals(listOf(provider.itemUri), provider.deletedUris)
    }

    @Test
    fun audioEntryPointCopiesOriginalBytesAndReportsTheProviderName() {
        val audio = File.createTempFile("export-audio-", ".m4a", app.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val stream = recordingStream()
            provider.displayName = "Müller_1.m4a"
            shadowOf(app.contentResolver).registerOutputStream(provider.itemUri, stream)

            val result = PdfExporter.exportAudioToDownloads(app, "Müller", audio)

            assertEquals("Downloads/LastMeeting/Müller_1.m4a", result.getOrThrow())
            assertEquals("Müller.m4a", provider.insertedValues!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
            assertEquals("audio/mp4", provider.insertedValues!!.getAsString(MediaStore.MediaColumns.MIME_TYPE))
            assertArrayEquals(audio.readBytes(), stream.toByteArray())
            assertEquals(0, provider.pending)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun exportReportsLocalizedFailureAndWarnsWhenCleanupFails() {
        val failure = IOException("Writer failed")
        provider.deleteFailure = SecurityException("Delete denied")
        shadowOf(app.contentResolver).registerOutputStream(provider.itemUri, recordingStream())

        val result = DownloadsExporter.export(app, "meeting.pdf", "application/pdf") { throw failure }

        assertTrue(result.isFailure)
        assertEquals(app.getString(R.string.msg_export_cleanup_failed), result.exceptionOrNull()!!.message)
        assertSame(failure, result.exceptionOrNull()!!.cause)
        assertSame(provider.deleteFailure, failure.suppressed.single().cause)
    }

    @Test
    fun ordinaryFailureUsesTheLocalizedMessageWithoutACleanupWarning() {
        shadowOf(app.contentResolver).registerOutputStream(provider.itemUri, recordingStream())
        val result = DownloadsExporter.export(app, "meeting.pdf", "application/pdf") { throw IOException("Write failed") }
        assertEquals(app.getString(R.string.msg_export_write_failed), result.exceptionOrNull()!!.message)
        assertRollback()
    }

    @Test
    fun aSuppressedCloseErrorDoesNotClaimThatCleanupFailed() {
        val failure = IOException("Write failed")
        val closeFailure = IOException("Close failed")
        shadowOf(app.contentResolver).registerOutputStream(provider.itemUri, recordingStream(closeFailure))
        val result = DownloadsExporter.export(app, "meeting.pdf", "application/pdf") { throw failure }
        assertEquals(app.getString(R.string.msg_export_write_failed), result.exceptionOrNull()!!.message)
        assertSame(failure, result.exceptionOrNull()!!.cause)
        assertSame(closeFailure, failure.suppressed.single())
        assertRollback()
    }

    private fun export(
        stream: OutputStream? = recordingStream(),
        writeContent: (OutputStream) -> Unit = { it.write("PDF contents".toByteArray()) }
    ): String = DownloadsExporter.writeMediaStore(app.contentResolver, "Müller_protocol.pdf", "application/pdf",
        openStream = { uri ->
            assertEquals(provider.itemUri, uri)
            provider.events += "open"
            stream
        }, writeContent = writeContent)

    private fun recordingStream(closeFailure: IOException? = null) = object : ByteArrayOutputStream() {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            assertEquals(1, provider.pending)
            provider.events += "write"
            super.write(bytes, offset, length)
        }

        override fun close() {
            assertEquals(1, provider.pending)
            provider.events += "close"
            super.close()
            closeFailure?.let { throw it }
        }
    }

    private fun assertRollback() {
        assertEquals(listOf(provider.itemUri), provider.deletedUris)
        assertTrue(provider.removed)
    }

    private class ExportProvider : ContentProvider() {
        val itemUri: Uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, 42)
        val events = mutableListOf<String>()
        val deletedUris = mutableListOf<Uri>()
        var insertedValues: ContentValues? = null
        var pending = -1
        var rejectInsert = false
        var publishCount = 1
        var publishFailure: RuntimeException? = null
        var deleteFailure: RuntimeException? = null
        var removed = false
        var returnName = true
        var displayName = "Müller_protocol_1.pdf"

        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/pdf"

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, uri)
            events += "insert"
            insertedValues = ContentValues(requireNotNull(values))
            pending = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING)
            return if (rejectInsert) null else itemUri
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            assertEquals(itemUri, uri)
            events += "publish"
            publishFailure?.let { throw it }
            if (publishCount == 1) pending = requireNotNull(values).getAsInteger(MediaStore.MediaColumns.IS_PENDING)
            return publishCount
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            assertEquals(itemUri, uri)
            events += "query"
            return MatrixCursor(arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)).apply {
                if (returnName) addRow(arrayOf(displayName))
            }
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            events += "delete"
            deletedUris += uri
            deleteFailure?.let { throw it }
            assertEquals(itemUri, uri)
            removed = true
            return 1
        }
    }
}
