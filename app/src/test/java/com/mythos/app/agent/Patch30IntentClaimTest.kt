package com.mythos.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** patch30: kalimat nyata dari rekaman layar (Codex native) yang sebelumnya lolos dan mengakhiri giliran tanpa tindakan. */
class Patch30IntentClaimTest {
    @Test fun niatBentukKiniDianggapBelumDikerjakan() {
        assertTrue(looksLikeUnfulfilledIntent("Saya lanjutkan PATCH 28–29 dengan memeriksa berkas kode sumber yang disebut di catatan, lalu menerapkan perubahan yang sesuai."))
        assertTrue(looksLikeUnfulfilledIntent("Saya membaca catatan patch 28–29 dan petunjuk rilisnya untuk menentukan perubahan yang diminta."))
    }

    @Test fun jawabanAkhirBiasaTidakDianggapNiat() {
        assertFalse(looksLikeUnfulfilledIntent("Saya membaca file itu. Isinya tiga baris dan semuanya valid."))
    }

    @Test fun klaimAlatTidakTersediaYangLebihLuasTertangkap() {
        assertTrue(claimsToolsUnavailable("Saya tidak bisa membaca atau mengubah arsip proyek melalui shell Termux."))
        assertTrue(claimsToolsUnavailable("Pada giliran ini jadi belum dapat memeriksa atau menerapkan PATCH 28–29."))
        assertTrue(claimsToolsUnavailable("Alat Mythos tidak tampil di sesi ini, jadi saya belum bisa mengeceknya."))
        assertTrue(claimsToolsUnavailable("Sesi ini tidak dapat menjalankan blok tersebut."))
        assertFalse(claimsToolsUnavailable("Saya membaca file itu dan hasilnya sudah benar."))
    }
}
