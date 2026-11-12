package com.example.lastmeeting.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.example.lastmeeting.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

internal object DownloadsExporter {
    fun export(
        context: Context,
        fileName: String,
        mimeType: String,
        writeContent: (OutputStream) -> Unit
    ): Result<String> = try {
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeMediaStore(context.contentResolver, fileName, mimeType, writeContent = writeContent)
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            writeLegacy(File(downloads, "LastMeeting"), fileName, writeContent = writeContent).absolutePath
        }
        Result.success(path)
    } catch (failure: Exception) {
        val message = if (failure.suppressed.any { it is CleanupFailure }) {
            R.string.msg_export_cleanup_failed
        } else {
            R.string.msg_export_write_failed
        }
        Result.failure(IOException(context.getString(message), failure))
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun writeMediaStore(
        resolver: ContentResolver,
        fileName: String,
        mimeType: String,
        openStream: (Uri) -> OutputStream? = { resolver.openOutputStream(it) },
        writeContent: (OutputStream) -> Unit
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LastMeeting")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create export destination")
        try {
            val stream = openStream(uri) ?: throw IOException("Unable to open export destination")
            stream.use(writeContent)
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Unable to publish export")

            // Providers can change the requested name to avoid a collision.
            val displayName = resolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                } else null
            }
            if (displayName.isNullOrBlank()) throw IOException("Unable to read exported filename")
            return "Downloads/LastMeeting/$displayName"
        } catch (failure: Exception) {
            cleanUp(failure) { resolver.delete(uri, null, null) == 1 }
            throw failure
        }
    }

    internal fun writeLegacy(
        directory: File,
        fileName: String,
        openStream: (File) -> OutputStream = { FileOutputStream(it) },
        writeContent: (OutputStream) -> Unit
    ): File {
        // Another export may create the directory between the first check and mkdirs().
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create export directory")
        }
        val file = reserveFile(directory, fileName)
        try {
            openStream(file).use(writeContent)
            return file
        } catch (failure: Exception) {
            cleanUp(failure) { file.delete() || !file.exists() }
            throw failure
        }
    }

    private fun reserveFile(directory: File, fileName: String): File {
        val stem = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.')
        var suffix = 0L
        while (true) {
            val name = if (suffix == 0L) fileName else "${stem}_$suffix.$extension"
            val file = File(directory, name)
            if (file.createNewFile()) return file
            suffix++
        }
    }

    private fun cleanUp(failure: Exception, delete: () -> Boolean) {
        try {
            if (!delete()) throw IOException("Unable to remove incomplete export")
        } catch (cleanup: Exception) {
            failure.addSuppressed(CleanupFailure(cleanup))
        }
    }

    private class CleanupFailure(cause: Exception) : IOException("Export cleanup failed", cause)
}
