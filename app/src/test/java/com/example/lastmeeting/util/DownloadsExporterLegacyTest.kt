package com.example.lastmeeting.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadsExporterLegacyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun repeatedPdfAndAudioExportsKeepEarlierContents() {
        for (name in listOf(ExportFileNames.pdf("Müller", "Protocol"), ExportFileNames.audio("Müller"))) {
            val first = write(name, "Earlier export")
            val second = write(name, "New export")
            assertNotEquals(first, second)
            assertEquals("Earlier export", first.readText())
            assertEquals("New export", second.readText())
        }
    }

    @Test
    fun sanitizerCollisionsDoNotReplaceExistingExports() {
        val first = write(ExportFileNames.pdf("Team Sync", "Protocol"), "First meeting")
        val second = write(ExportFileNames.pdf("Team/Sync", "Protocol"), "Second meeting")
        assertNotEquals(first, second)
        assertEquals("First meeting", first.readText())
        assertEquals("Second meeting", second.readText())
    }

    @Test
    fun simultaneousExportsReserveDifferentFilesEvenWhenCreatingTheDirectory() {
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val directory = File(temporaryFolder.root, "LastMeeting")
        try {
            val futures = (1..8).map { index ->
                executor.submit<File> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    DownloadsExporter.writeLegacy(directory, "Müller.m4a") { it.write("Recording $index".toByteArray()) }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val files = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(8, files.toSet().size)
            assertEquals((1..8).map { "Recording $it" }.toSet(), files.map { it.readText() }.toSet())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedWriteClosesTheStreamAndDeletesOnlyItsNewFile() {
        val existing = write("meeting.pdf", "Earlier export")
        val failure = IOException("Writer failed")
        lateinit var stream: OutputStream
        val thrown = assertThrows(IOException::class.java) {
            DownloadsExporter.writeLegacy(temporaryFolder.root, existing.name) {
                stream = it
                it.write("Partial export".toByteArray())
                throw failure
            }
        }
        assertSame(failure, thrown)
        assertEquals(listOf(existing), temporaryFolder.root.listFiles()!!.toList())
        assertEquals("Earlier export", existing.readText())
        assertThrows(IOException::class.java) { stream.write(0) }
    }

    @Test
    fun failedOpenRemovesTheReservationAndPreservesEarlierExports() {
        val existing = write("meeting.pdf", "Earlier export")
        val failure = IOException("Open failed")
        val thrown = assertThrows(IOException::class.java) {
            DownloadsExporter.writeLegacy(temporaryFolder.root, existing.name, openStream = { throw failure }) {
                error("Writer must not run")
            }
        }
        assertSame(failure, thrown)
        assertEquals(listOf(existing), temporaryFolder.root.listFiles()!!.toList())
        assertEquals("Earlier export", existing.readText())
    }

    @Test
    fun failedCloseAlsoRemovesOnlyTheNewFile() {
        val existing = write("meeting.pdf", "Earlier export")
        val failure = IOException("Close failed")
        val thrown = assertThrows(IOException::class.java) {
            DownloadsExporter.writeLegacy(temporaryFolder.root, existing.name, openStream = { file ->
                object : FileOutputStream(file) {
                    override fun close() {
                        super.close()
                        throw failure
                    }
                }
            }) { it.write("New export".toByteArray()) }
        }
        assertSame(failure, thrown)
        assertEquals(listOf(existing), temporaryFolder.root.listFiles()!!.toList())
        assertEquals("Earlier export", existing.readText())
    }

    @Test
    fun directoryCreationFailureDoesNotTouchTheExistingFile() {
        val blockingFile = temporaryFolder.newFile("LastMeeting").apply { writeText("Keep me") }
        assertThrows(IOException::class.java) {
            DownloadsExporter.writeLegacy(blockingFile, "meeting.pdf") { error("Writer must not run") }
        }
        assertEquals("Keep me", blockingFile.readText())
    }

    private fun write(name: String, contents: String): File =
        DownloadsExporter.writeLegacy(temporaryFolder.root, name) { it.write(contents.toByteArray()) }
}
