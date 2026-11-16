package com.mythos.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kartu draf email = salinan 1:1 tampilan surat Gmail dan tidak terpengaruh pengaturan tampilan di luar kartu. */
class EmailDraftCardLookTest {
    private val flipBody = """
        Halo Kak Easy

        Terima kasih telah menghubungi Flip

        Untuk dapat melakukan peninjauan lebih lanjut, mohon dapat mengirimkan kembali dokumen pendukung:

        1. Surat keterangan resmi dari BCA terkait status rekening sumber dana.
        2. Surat pernyataan persetujuan pengalihan refund yang ditandatangani oleh
        kedua pihak.
        3. KTP pemilik akun Flip dan pemilik dana.

        Salam,
        Tim Flip
    """.trimIndent()

    @Test fun isiSuratDipecahMenjadiParagrafDanDaftarBernomor() {
        val b = parseEmailBody(flipBody)
        assertEquals(7, b.size)
        assertEquals(EmailBodyBlock.Para("Halo Kak Easy"), b[0])
        assertEquals(EmailBodyBlock.Item("1.", "Surat keterangan resmi dari BCA terkait status rekening sumber dana."), b[3])
        // Baris lanjutan menyatu ke butir di atasnya.
        assertEquals(EmailBodyBlock.Item("2.", "Surat pernyataan persetujuan pengalihan refund yang ditandatangani oleh kedua pihak."), b[4])
        // Tanda tangan dua baris tetap satu blok dengan jeda baris.
        assertEquals(EmailBodyBlock.Para("Salam,\nTim Flip"), b[6])
    }

    @Test fun daftarBerpoinDanBodyKosongAman() {
        val b = parseEmailBody("- satu\n* dua\nteks")
        assertEquals(listOf(EmailBodyBlock.Item("•", "satu"), EmailBodyBlock.Item("•", "dua teks")), b)
        assertTrue(parseEmailBody("  \n\n").isEmpty())
    }

    @Test fun namaDanRingkasanPenerimaGayaGmail() {
        assertEquals("Lapor Flip", draftSenderName(listOf("lapor@flip.id")))
        assertEquals("Info Ultravoucher", draftSenderName(listOf("info@ultravoucher.co.id")))
        assertEquals("Draf email", draftSenderName(emptyList()))
        assertEquals("kepada lapor@flip.id", draftToSummary(listOf("lapor@flip.id")))
        assertEquals("kepada a@x.id +2", draftToSummary(listOf("a@x.id", "b@x.id", "c@x.id")))
    }

    @Test fun kartuTidakMengikutiFontUkuranTemaGelapAtauGayaFontPengguna() {
        val src = File("src/main/java/com/mythos/app/ui/EmailDraftCard.kt").readText()
        // Skala sendiri: 1 dp = 1 satuan desain, fontScale terkunci 1 (tidak ikut FontScale aplikasi maupun font perangkat).
        assertTrue(src.contains("LocalDensity provides Density(widthPx / GMAIL_DESIGN_WIDTH, 1f)"))
        assertEquals(974f, GMAIL_DESIGN_WIDTH, 0f)
        // Tidak memakai font tema pengguna maupun mode gelap sistem.
        assertFalse(src.contains("MythosSans"))
        assertFalse(src.contains("FontTheme"))
        assertFalse(src.contains("isSystemInDarkTheme"))
        assertFalse(src.contains("MaterialTheme"))
    }
}
