package com.example.lastmeeting.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream

class PdfExporterCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sameTitleSharesKeepIndependentContents() {
        val firstContents = "Meeting A transcript".toByteArray()
        val secondContents = "Meeting B transcript".toByteArray()
        val first = PdfExporter.writeCachePdf(temporaryFolder.root, "Team Sync", "Transcript") {
            it.write(firstContents)
        }
        val second = PdfExporter.writeCachePdf(temporaryFolder.root, "Team Sync", "Transcript") {
            it.write(secondContents)
        }

        assertNotEquals(first, second)
        assertArrayEquals(firstContents, first.readBytes())
        assertArrayEquals(secondContents, second.readBytes())
        assertEquals(temporaryFolder.root, first.parentFile)
        assertTrue(first.name.startsWith("Team_Sync_transcript_"))
        assertTrue(first.name.endsWith(".pdf"))
    }

    @Test
    fun titlesThatSanitizeIdenticallyStillGetIndependentFiles() {
        val first = PdfExporter.writeCachePdf(temporaryFolder.root, "Team Sync", "Transcript") {
            it.write("Meeting A".toByteArray())
        }
        val second = PdfExporter.writeCachePdf(temporaryFolder.root, "Team/Sync", "Transcript") {
            it.write("Meeting B".toByteArray())
        }

        assertNotEquals(first, second)
        assertEquals("Meeting A", first.readText())
        assertEquals("Meeting B", second.readText())
    }

    @Test
    fun failedWriteRemovesOnlyTheIncompleteFileAndClosesTheStream() {
        val existing = PdfExporter.writeCachePdf(temporaryFolder.root, "Team Sync", "Transcript") {
            it.write("Already shared".toByteArray())
        }
        // A previous export must survive even when it is old and a later export fails.
        assertTrue(existing.setLastModified(1L))
        val failure = IOException("PDF rendering failed")
        lateinit var failedStream: OutputStream

        val thrown = assertThrows(IOException::class.java) {
            PdfExporter.writeCachePdf(temporaryFolder.root, "Team Sync", "Transcript") {
                failedStream = it
                it.write("Incomplete PDF".toByteArray())
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertArrayEquals(arrayOf(existing), temporaryFolder.root.listFiles())
        assertEquals("Already shared", existing.readText())
        assertThrows(IOException::class.java) { failedStream.write(0) }
    }
}
