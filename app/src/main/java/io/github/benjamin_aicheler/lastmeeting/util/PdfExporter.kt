package io.github.benjamin_aicheler.lastmeeting.util

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.benjamin_aicheler.lastmeeting.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

internal data class PaginatedLineSlice(
    val startLine: Int,
    val endLine: Int,
    val startsOnNewPage: Boolean,
    val height: Float
)

object PdfExporter {

    internal const val SHARED_PDF_DIR_NAME = "shared_pdfs"
    internal const val SHARED_PDF_RETENTION_MS = 24L * 60L * 60L * 1000L

    fun exportToPdf(
        context: Context,
        title: String,
        typeLabel: String,
        textContent: String
    ): Result<String> {
        return DownloadsExporter.export(context, ExportFileNames.pdf(title, typeLabel), "application/pdf") { stream ->
            writePdfDocument(title, typeLabel, textContent, stream)
        }
    }

    fun exportToCachePdf(
        context: Context,
        title: String,
        typeLabel: String,
        textContent: String
    ): Result<File> {
        return try {
            pruneExpiredSharedPdfs(context.cacheDir)
            val outFile = writeCachePdf(context.cacheDir, title, typeLabel) { stream ->
                writePdfDocument(title, typeLabel, textContent, stream)
            }
            Result.success(outFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun writeCachePdf(
        cacheDir: File,
        title: String,
        typeLabel: String,
        writeDocument: (OutputStream) -> Unit
    ): File {
        val sharedDir = File(cacheDir, SHARED_PDF_DIR_NAME)
        if (!sharedDir.exists() && !sharedDir.mkdirs() && !sharedDir.isDirectory) {
            throw IOException("Could not create shared PDF directory: ${sharedDir.absolutePath}")
        }
        val prefix = ExportFileNames.pdf(title, typeLabel).removeSuffix(".pdf") + "_"
        // Reserve a new path for every share: existing URI grants must keep their contents.
        val outFile = File.createTempFile(prefix, ".pdf", sharedDir)
        try {
            FileOutputStream(outFile).use(writeDocument)
            return outFile
        } catch (e: Exception) {
            outFile.delete()
            throw e
        }
    }

    internal fun pruneExpiredSharedPdfs(
        cacheDir: File,
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = SHARED_PDF_RETENTION_MS
    ): Int {
        val sharedDir = File(cacheDir, SHARED_PDF_DIR_NAME)
        if (!sharedDir.isDirectory) return 0
        val cutoffMillis = nowMillis - maxAgeMillis
        var deletedCount = 0
        sharedDir.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.lastModified() < cutoffMillis && file.delete()) {
                deletedCount++
            }
        }
        return deletedCount
    }

    fun exportAudioToDownloads(
        context: Context,
        title: String,
        audioFile: File
    ): Result<String> {
        if (!audioFile.exists()) {
            return Result.failure(Exception(context.getString(R.string.msg_audio_not_found)))
        }
        return DownloadsExporter.export(context, ExportFileNames.audio(title), "audio/mp4") { out ->
            audioFile.inputStream().use { input ->
                input.copyTo(out)
            }
        }
    }

    internal fun paginateLineHeights(
        lineHeights: List<Float>,
        currentY: Float,
        pageTopY: Float,
        pageBottomY: Float
    ): Pair<List<PaginatedLineSlice>, Float> {
        if (lineHeights.isEmpty()) {
            return emptyList<PaginatedLineSlice>() to currentY
        }

        val slices = mutableListOf<PaginatedLineSlice>()
        var y = currentY
        var lineIndex = 0
        while (lineIndex < lineHeights.size) {
            val startsOnNewPage = y > pageTopY && y + lineHeights[lineIndex] > pageBottomY
            if (startsOnNewPage) {
                y = pageTopY
            }

            val startLine = lineIndex
            var endLine = lineIndex
            var sliceHeight = lineHeights[lineIndex]
            while (
                endLine + 1 < lineHeights.size &&
                y + sliceHeight + lineHeights[endLine + 1] <= pageBottomY
            ) {
                endLine++
                sliceHeight += lineHeights[endLine]
            }

            slices += PaginatedLineSlice(
                startLine = startLine,
                endLine = endLine,
                startsOnNewPage = startsOnNewPage,
                height = sliceHeight
            )
            y += sliceHeight
            lineIndex = endLine + 1
        }
        return slices to y
    }

    internal fun buildStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int
    ): StaticLayout {
        val builder = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.15f)
            .setIncludePad(true)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
        }
        return builder.build()
    }

    internal fun writePdfDocument(
        title: String,
        typeLabel: String,
        textContent: String,
        outputStream: OutputStream
    ) {
        val pdfDocument = PdfDocument()
        try {
            val pageWidth = 595 // A4 width in points (72 dpi)
            val pageHeight = 842 // A4 height in points
            val margin = 40
            val contentWidth = pageWidth - (margin * 2)
            val pageTopY = margin.toFloat()
            val pageBottomY = (pageHeight - margin).toFloat()

            var pageNumber = 1
            var page = pdfDocument.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            )
            var canvas = page.canvas
            var currentY = pageTopY

            fun startNextPage() {
                pdfDocument.finishPage(page)
                pageNumber++
                page = pdfDocument.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                )
                canvas = page.canvas
                currentY = pageTopY
            }

            fun drawPaginatedLayout(layout: StaticLayout) {
                val lineHeights = (0 until layout.lineCount).map { i ->
                    (layout.getLineBottom(i) - layout.getLineTop(i)).toFloat()
                }
                val (slices, _) = paginateLineHeights(lineHeights, currentY, pageTopY, pageBottomY)
                for (slice in slices) {
                    if (slice.startsOnNewPage) {
                        startNextPage()
                    }
                    val sliceTop = layout.getLineTop(slice.startLine).toFloat()
                    val sliceBottom = layout.getLineBottom(slice.endLine).toFloat()
                    canvas.save()
                    canvas.translate(margin.toFloat(), currentY - sliceTop)
                    canvas.clipRect(0f, sliceTop, contentWidth.toFloat(), sliceBottom)
                    layout.draw(canvas)
                    canvas.restore()
                    currentY += (sliceBottom - sliceTop)
                }
            }

            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 18f
                color = Color.BLACK
            }

            val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                textSize = 12f
                color = Color.DKGRAY
            }

            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT
                textSize = 11f
                color = Color.BLACK
            }

            val linePaint = Paint().apply {
                color = Color.LTGRAY
                strokeWidth = 1f
            }

            // Title (paginated across pages when long)
            drawPaginatedLayout(buildStaticLayout(title, titlePaint, contentWidth))
            currentY += 6f

            // Reserve subtitle + divider together so the divider stays attached to the subtitle.
            val subtitleLayout = buildStaticLayout(typeLabel, subtitlePaint, contentWidth)
            val subtitleDividerGap = 8f
            val dividerThickness = 1f
            val afterDividerGap = 14f
            val subtitleBlockHeight = subtitleLayout.height.toFloat() + subtitleDividerGap + dividerThickness
            if (currentY > pageTopY &&
                currentY + subtitleBlockHeight > pageBottomY &&
                subtitleBlockHeight <= (pageBottomY - pageTopY)
            ) {
                startNextPage()
            }

            drawPaginatedLayout(subtitleLayout)
            currentY += subtitleDividerGap
            if (currentY > pageTopY && currentY + dividerThickness > pageBottomY) {
                startNextPage()
            }
            canvas.drawLine(
                margin.toFloat(),
                currentY,
                (pageWidth - margin).toFloat(),
                currentY,
                linePaint
            )
            currentY += afterDividerGap

            // Body paragraphs (preserving blank lines and pagination)
            val paragraphs = textContent
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .split("\n")
            for (paragraph in paragraphs) {
                drawPaginatedLayout(buildStaticLayout(paragraph, bodyPaint, contentWidth))
            }

            pdfDocument.finishPage(page)
            pdfDocument.writeTo(outputStream)
        } finally {
            pdfDocument.close()
        }
    }
}
