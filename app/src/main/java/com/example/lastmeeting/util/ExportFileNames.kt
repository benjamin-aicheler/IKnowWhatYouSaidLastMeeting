package com.example.lastmeeting.util

import java.text.Normalizer
import java.util.Locale

internal object ExportFileNames {
    private val unsafeCharacters = Regex("[^\\p{L}\\p{N}\\p{M}_-]+")
    private val letterOrNumber = Regex("[\\p{L}\\p{N}]")

    fun pdf(title: String, typeLabel: String): String =
        "${sanitize(title, "meeting", 120)}_${sanitize(typeLabel.lowercase(Locale.ROOT), "document", 40)}.pdf"

    fun audio(title: String): String = "${sanitize(title, "meeting", 120)}.m4a"

    private fun sanitize(value: String, fallback: String, maxBytes: Int): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        val safe = normalized.replace(unsafeCharacters, "_").trim('_', '-')
        if (!letterOrNumber.containsMatchIn(safe)) return fallback

        // Bound UTF-8 bytes, preserving whole code points and leaving room for collision suffixes
        // and the random suffix used by cache sharing on filesystems with 255-byte name limits.
        var end = 0
        var bytes = 0
        while (end < safe.length) {
            val next = end + Character.charCount(safe.codePointAt(end))
            val nextBytes = safe.substring(end, next).toByteArray(Charsets.UTF_8).size
            if (bytes + nextBytes > maxBytes) break
            bytes += nextBytes
            end = next
        }
        return safe.substring(0, end).trimEnd('_', '-').ifBlank { fallback }
    }
}
