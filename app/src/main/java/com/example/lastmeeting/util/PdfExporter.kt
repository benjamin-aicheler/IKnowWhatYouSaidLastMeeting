package com.example.lastmeeting.util

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.lastmeeting.R
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object PdfExporter {

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
        val prefix = ExportFileNames.pdf(title, typeLabel).removeSuffix(".pdf") + "_"
        // Reserve a new path for every share: existing URI grants must keep their contents.
        val outFile = File.createTempFile(prefix, ".pdf", cacheDir)
        try {
            FileOutputStream(outFile).use(writeDocument)
            return outFile
        } catch (e: Exception) {
            outFile.delete()
            throw e
        }
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

    private fun writePdfDocument(
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

            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            var page = pdfDocument.startPage(pageInfo)
            var canvas = page.canvas

            val titlePaint = Paint().apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 18f
                color = Color.BLACK
            }

            val subtitlePaint = Paint().apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                textSize = 12f
                color = Color.DKGRAY
            }

            val bodyPaint = Paint().apply {
                typeface = Typeface.DEFAULT
                textSize = 11f
                color = Color.BLACK
            }

            var y = margin.toFloat() + 20f

            // Draw Header (wrapped if title is long)
            for (titleLine in wrapText(title, titlePaint, contentWidth.toFloat())) {
                canvas.drawText(titleLine, margin.toFloat(), y, titlePaint)
                y += 24f
            }
            canvas.drawText(typeLabel, margin.toFloat(), y, subtitlePaint)
            y += 20f

            // Draw line divider
            val linePaint = Paint().apply {
                color = Color.LTGRAY
                strokeWidth = 1f
            }
            canvas.drawLine(margin.toFloat(), y, (pageWidth - margin).toFloat(), y, linePaint)
            y += 20f

            // Wrap text lines
            val lines = textContent.split("\n")
            for (line in lines) {
                val wrappedLines = wrapText(line, bodyPaint, contentWidth.toFloat())
                for (wrappedLine in wrappedLines) {
                    if (y > pageHeight - margin - 20) {
                        pdfDocument.finishPage(page)
                        pageNumber++
                        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        y = margin.toFloat() + 20f
                    }
                    canvas.drawText(wrappedLine, margin.toFloat(), y, bodyPaint)
                    y += 16f
                }
            }

            pdfDocument.finishPage(page)
            pdfDocument.writeTo(outputStream)
        } finally {
            pdfDocument.close()
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val result = mutableListOf<String>()
        val words = text.split(" ")
        var currentLine = ""

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine = testLine
            } else {
                if (currentLine.isNotEmpty()) {
                    result.add(currentLine)
                    currentLine = ""
                }
                if (paint.measureText(word) <= maxWidth) {
                    currentLine = word
                } else {
                    // Break single word that exceeds maxWidth
                    var chunk = ""
                    for (ch in word) {
                        val nextChunk = chunk + ch
                        if (paint.measureText(nextChunk) <= maxWidth) {
                            chunk = nextChunk
                        } else {
                            if (chunk.isNotEmpty()) result.add(chunk)
                            chunk = ch.toString()
                        }
                    }
                    currentLine = chunk
                }
            }
        }
        if (currentLine.isNotEmpty()) {
            result.add(currentLine)
        }
        return result
    }
}
