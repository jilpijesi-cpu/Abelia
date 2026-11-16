package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalExplainerTest {
    @Test fun hapusBerkasDijelaskanDenganPeringatan() {
        val e = explainApproval("Delete", "Delete /a/b.txt")!!
        assertEquals(listOf("Berkas: /a/b.txt"), e.steps)
        assertTrue(e.impact.single().contains("tidak bisa dikembalikan"))
        assertEquals("Delete /a/b.txt", e.technical)
    }

    @Test fun tulisDanEditDijelaskan() {
        val w = explainApproval("Write", "Write app/x.kt (120 karakter)")!!
        assertEquals(listOf("Berkas: app/x.kt (±120 karakter)"), w.steps)
        assertNotNull(explainApproval("Edit", "Edit app/x.kt"))
    }

    @Test fun webFetchMenyebutAlamatDanInternet() {
        val e = explainApproval("WebFetch", "WebFetch https://contoh.id/halaman")!!
        assertEquals(listOf("Alamat: https://contoh.id/halaman"), e.steps)
        assertEquals(listOf("Akan memakai internet."), e.impact)
    }

    @Test fun peringatanKonteksEksternalDiteruskan() {
        val e = explainApproval("WebFetch", "WebFetch https://contoh.id\n\nKonteks memuat konten eksternal; tindakan ini perlu ditinjau.")!!
        assertNotNull(e.note)
        assertTrue(e.note!!.startsWith("Konteks memuat"))
    }

    @Test fun alatLainDanFormatAsingTidakMematahkanKartu() {
        assertNull(explainApproval("ExitPlanMode", "rencana panjang"))
        assertNull(explainApproval("Termux", "alat Termux Mythos sudah dihapus; tampil sebagai teks mentah"))
        assertNull(explainApproval("Delete", "format tak dikenal"))
    }

    @Test fun ringkasanKartuKecilSatuBarisPendek() {
        assertEquals("Buka flip.id/about", shortApproval("WebFetch", "WebFetch https://flip.id/about\n\nKonteks memuat konten eksternal dan alamat baru."))
        assertEquals("Hapus old.txt", shortApproval("Delete", "Delete old.txt"))
        assertEquals("Tulis app/x.kt", shortApproval("Write", "Write app/x.kt (120 karakter)"))
        assertEquals("Ubah app/x.kt", shortApproval("Edit", "Edit app/x.kt"))
        assertEquals("Kirim email ke a@b.co", shortApproval("GmailDraftSend", "GmailDraftSend [send] Kepada: a@b.co | Subjek: s | Isi: b"))
        assertEquals("Buat draf email ke a@b.co", shortApproval("GmailDraftSend", "GmailDraftSend [create_draft] Kepada: a@b.co | Subjek: s | Isi: b"))
        assertTrue(shortApproval("GithubCreatePR", "x".repeat(500)).length <= 90)
        assertTrue(shortApproval("Delete", "Delete " + "d/".repeat(60) + "f.txt").length < 60)
    }

    @Test fun peringatanKontenEksternalTerdeteksi() {
        assertTrue(hasExternalWarning("WebFetch https://x.id\n\nKonteks memuat konten eksternal."))
        assertFalse(hasExternalWarning("WebFetch https://x.id"))
    }
}

