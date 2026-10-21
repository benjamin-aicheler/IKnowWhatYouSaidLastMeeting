package com.example.lastmeeting.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        return try {
            val fileName = buildFileName(title, typeLabel, "pdf")
            val outputStream: OutputStream?
            val filePathDescription: String

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LastMeeting")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Result.failure(Exception("Failed to create MediaStore entry."))
                outputStream = context.contentResolver.openOutputStream(uri)
                filePathDescription = "Downloads/LastMeeting/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "LastMeeting")
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, fileName)
                outputStream = FileOutputStream(targetFile)
                filePathDescription = targetFile.absolutePath
            }

            if (outputStream == null) {
                return Result.failure(Exception("Unable to open output stream for PDF."))
            }

            outputStream.use { stream ->
                writePdfDocument(title, typeLabel, textContent, stream)
            }

            Result.success(filePathDescription)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun exportToCachePdf(
        context: Context,
        title: String,
        typeLabel: String,
        textContent: String
    ): Result<File> {
        return try {
            val fileName = buildFileName(title, typeLabel, "pdf")
            val outFile = File(context.cacheDir, fileName)
            FileOutputStream(outFile).use { stream ->
                writePdfDocument(title, typeLabel, textContent, stream)
            }
            Result.success(outFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun exportAudioToDownloads(
        context: Context,
        title: String,
        audioFile: File
    ): Result<String> {
        if (!audioFile.exists()) {
            return Result.failure(Exception("Audio file not found."))
        }
        return try {
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "meeting" }
            val fileName = "${sanitizedTitle}.m4a"
            val outputStream: OutputStream?
            val filePathDescription: String

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LastMeeting")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Result.failure(Exception("Failed to create MediaStore entry."))
                outputStream = context.contentResolver.openOutputStream(uri)
                filePathDescription = "Downloads/LastMeeting/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "LastMeeting")
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, fileName)
                outputStream = FileOutputStream(targetFile)
                filePathDescription = targetFile.absolutePath
            }

            if (outputStream == null) {
                return Result.failure(Exception("Unable to open output stream for audio."))
            }

            outputStream.use { out ->
                audioFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            Result.success(filePathDescription)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildFileName(title: String, typeLabel: String, extension: String): String {
        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "meeting" }
        val sanitizedType = typeLabel.replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase()
        return "${sanitizedTitle}_${sanitizedType}.$extension"
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
