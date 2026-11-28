package io.github.benjamin_aicheler.lastmeeting.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPaginationTest {

    @Test
    fun paginateLineHeightsSplitsLongHeaderAcrossPages() {
        // A4 usable height: pageBottomY (802) - pageTopY (40) = 762pt.
        // 40 lines x 24pt = 960pt -> 31 lines (744pt) on page 1, 9 lines (216pt) on page 2.
        val lineHeights = List(40) { 24f }
        val (slices, finalY) = PdfExporter.paginateLineHeights(
            lineHeights = lineHeights,
            currentY = 40f,
            pageTopY = 40f,
            pageBottomY = 802f
        )

        assertEquals(2, slices.size)
        assertEquals(PaginatedLineSlice(startLine = 0, endLine = 30, startsOnNewPage = false, height = 744f), slices[0])
        assertEquals(PaginatedLineSlice(startLine = 31, endLine = 39, startsOnNewPage = true, height = 216f), slices[1])
        assertEquals(40f + 216f, finalY, 0.001f)
    }

    @Test
    fun paginateLineHeightsRespectsExactPageBoundaryAndNonTopContinuation() {
        // Remaining space on page 1 is 802 - 762 = 40pt (two 20pt lines fit exactly).
        val (slices, finalY) = PdfExporter.paginateLineHeights(
            lineHeights = listOf(20f, 20f, 16f),
            currentY = 762f,
            pageTopY = 40f,
            pageBottomY = 802f
        )

        assertEquals(2, slices.size)
        assertEquals(PaginatedLineSlice(startLine = 0, endLine = 1, startsOnNewPage = false, height = 40f), slices[0])
        assertEquals(PaginatedLineSlice(startLine = 2, endLine = 2, startsOnNewPage = true, height = 16f), slices[1])
        assertEquals(56f, finalY, 0.001f)
    }

    @Test
    fun paginateLineHeightsMakesProgressOnOversizedSingleLineWithoutEmptyPageLoop() {
        val (slices, finalY) = PdfExporter.paginateLineHeights(
            lineHeights = listOf(900f, 20f),
            currentY = 40f,
            pageTopY = 40f,
            pageBottomY = 802f
        )

        assertEquals(2, slices.size)
        // Fresh page (y == pageTopY) draws the oversized line immediately rather than emitting a blank page.
        assertFalse(slices[0].startsOnNewPage)
        assertEquals(0, slices[0].startLine)
        assertEquals(0, slices[0].endLine)
        // Following line overflows past pageBottomY and moves cleanly to a new page.
        assertTrue(slices[1].startsOnNewPage)
        assertEquals(1, slices[1].startLine)
        assertEquals(60f, finalY, 0.001f)
    }

    @Test
    fun paginateLineHeightsHandlesEmptyListWithoutAdvancingY() {
        val (slices, finalY) = PdfExporter.paginateLineHeights(
            lineHeights = emptyList(),
            currentY = 128f,
            pageTopY = 40f,
            pageBottomY = 802f
        )

        assertTrue(slices.isEmpty())
        assertEquals(128f, finalY, 0.001f)
    }
}
