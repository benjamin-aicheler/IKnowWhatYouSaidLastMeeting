package io.github.benjamin_aicheler.lastmeeting.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfExporterLayoutTest {

    @Test
    fun buildStaticLayoutPreservesSurrogatePairsCombiningMarksAndBidiAcrossWrappedLines() {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
        val narrowWidth = 110
        val inputs = listOf(
            "abc" + "😀".repeat(60) + "xyz",
            "e\u0301".repeat(80),
            "Meeting مرحبا 2026 ".repeat(15),
            "https://example.com/" + "segment/".repeat(35) + "😀😀"
        )

        for (input in inputs) {
            val layout = PdfExporter.buildStaticLayout(input, paint, narrowWidth)
            assertTrue("Expected multi-line wrapping for input length ${input.length}", layout.lineCount >= 2)

            val reconstructed = StringBuilder()
            for (lineIndex in 0 until layout.lineCount) {
                val start = layout.getLineStart(lineIndex)
                val end = layout.getLineEnd(lineIndex)
                val lineSlice = input.substring(start, end)
                if (lineSlice.isNotEmpty()) {
                    assertFalse(
                        "Line $lineIndex ended on orphaned high surrogate",
                        Character.isHighSurrogate(lineSlice.last())
                    )
                    assertFalse(
                        "Line $lineIndex started on orphaned low surrogate",
                        Character.isLowSurrogate(lineSlice.first())
                    )
                }
                reconstructed.append(lineSlice)
            }
            assertEquals(input, reconstructed.toString())
        }
    }

    @Test
    fun longMultiPageHeaderAndBlankParagraphBodyRenderAcrossMultiplePages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val longTitle = (1..55).joinToString(separator = "\n") { idx ->
            "Multi-Page Header Line $idx — Team Review 😀"
        }
        val body = "Opening paragraph.\n\nSecond paragraph after blank line.\n" +
            (1..25).joinToString(separator = "\n") { "Action item #$it" }

        var outputFile: File? = null
        try {
            outputFile = PdfExporter.exportToCachePdf(
                context = context,
                title = longTitle,
                typeLabel = "Transcript",
                textContent = body
            ).getOrThrow()

            ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    assertTrue("Expected >= 2 PDF pages, got ${renderer.pageCount}", renderer.pageCount >= 2)
                    for (pageIndex in 0 until 2) {
                        renderer.openPage(pageIndex).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                page.width,
                                page.height,
                                Bitmap.Config.ARGB_8888
                            )
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                assertTrue(
                                    "Expected non-blank rendered content on page $pageIndex",
                                    pixels.any { it != Color.WHITE }
                                )
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            outputFile?.delete()
        }
    }
}
