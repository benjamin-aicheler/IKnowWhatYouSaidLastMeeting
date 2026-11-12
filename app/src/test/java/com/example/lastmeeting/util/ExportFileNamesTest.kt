package com.example.lastmeeting.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ExportFileNamesTest {
    @Test
    fun unicodeLettersNumbersAndCombiningMarksArePreserved() {
        assertEquals("Müller_protokoll.pdf", ExportFileNames.pdf("Müller", "Protokoll"))
        assertEquals("東京_١٢.m4a", ExportFileNames.audio("東京 ١٢"))
        assertEquals("किरण.m4a", ExportFileNames.audio("किरण"))
        assertEquals("𐐀.m4a", ExportFileNames.audio("𐐀"))
    }

    @Test
    fun equivalentUnicodeSpellingsProduceTheSameName() {
        assertEquals(ExportFileNames.audio("Müller"), ExportFileNames.audio("Mu\u0308ller"))
        assertEquals(ExportFileNames.pdf("Müller", "Résumé"), ExportFileNames.pdf("Mu\u0308ller", "Re\u0301sume\u0301"))
    }

    @Test
    fun separatorsAndPunctuationCannotEscapeTheDestinationDirectory() {
        assertEquals("Müller_Plan.m4a", ExportFileNames.audio("../../Müller\\Plan:*?"))
        val pdf = ExportFileNames.pdf("../Müller", "../../Protokoll")
        assertEquals("Müller_protokoll.pdf", pdf)
        assertFalse(pdf.contains('/'))
        assertFalse(pdf.contains('\\'))
    }

    @Test
    fun emptyAndUnusableNamesHaveSafeFallbacks() {
        assertEquals("meeting.m4a", ExportFileNames.audio(""))
        assertEquals("meeting.m4a", ExportFileNames.audio("/../💬_-"))
        assertEquals("meeting_document.pdf", ExportFileNames.pdf("\u0308", ""))
    }

    @Test
    fun longNamesLeaveRoomForUniqueSuffixesWithoutSplittingCodePoints() {
        for (letter in listOf("会", "𐐀")) {
            val pdf = ExportFileNames.pdf(letter.repeat(200), letter.repeat(200))
            assertTrue(pdf.startsWith(letter))
            assertTrue(pdf.endsWith(".pdf"))
            assertTrue(pdf.toByteArray(Charsets.UTF_8).size < 200)
            assertEquals(pdf, pdf.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            assertTrue(ExportFileNames.audio(letter.repeat(200)).toByteArray(Charsets.UTF_8).size < 200)
        }
    }

    @Test
    fun typeNamesUseTheSameCaseAcrossDeviceLocales() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("Meeting_transcript.pdf", ExportFileNames.pdf("Meeting", "TRANSCRIPT"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
