package com.mythos.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Semua kartu (draf email, berkas, perubahan, konfirmasi) bergaya Gmail dan tidak terpengaruh font/ukuran pengaturan pengguna. */
class CardsFixedStyleTest {
    private fun src(name: String) = File("src/main/java/com/mythos/app/ui/$name").readText()

    @Test fun kartuTidakMemakaiGayaFontPenggunaDanTemaGelap() {
        for (f in listOf("EmailDraftCard.kt", "FileCard.kt", "ChangesCard.kt", "ConfirmCard.kt")) {
            val s = src(f)
            assertFalse("$f memakai MythosSans", s.contains("MythosSans"))
            assertFalse("$f memakai FontTheme", s.contains("FontTheme"))
            assertFalse("$f mengikuti mode gelap", s.contains("isSystemInDarkTheme"))
        }
    }

    @Test fun kartuBerkasPerubahanDanKonfirmasiMengunciSkalaFont() {
        for (f in listOf("FileCard.kt", "ChangesCard.kt", "ConfirmCard.kt")) assertTrue("$f belum memakai FixedScale", src(f).contains("FixedScale {"))
        assertTrue("CopyBox belum memakai FixedScale", src("CopyBlocks.kt").contains("FixedScale {"))
        assertTrue(src("GmailStyle.kt").contains("LocalDensity provides Density(d.density, 1f)"))
    }
}
