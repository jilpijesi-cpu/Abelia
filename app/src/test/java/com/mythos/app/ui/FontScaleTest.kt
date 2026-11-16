package com.mythos.app.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ukuran & ketebalan font diatur di dalam aplikasi dan tidak boleh mengikuti pengaturan font perangkat. */
class FontScaleTest {
    @After fun reset() { FontScale.select(FontScale.DEFAULT_STEP); FontTheme.setWeight(0) }

    @Test fun limaLangkahDenganDefaultDiLangkahKedua() {
        assertEquals(5, FontScale.scales.size)
        assertEquals(listOf("Kecil", "Default", "Besar", "Sangat Besar", "Terbesar"), FontScale.labels)
        assertEquals(1, FontScale.DEFAULT_STEP)
        assertEquals(1.0f, FontScale.scales[FontScale.DEFAULT_STEP], 0f)
    }

    @Test fun langkahDiBatasiDalamRentang() {
        FontScale.select(99); assertEquals(4, FontScale.step)
        FontScale.select(-3); assertEquals(0, FontScale.step)
        FontScale.select(2); assertEquals(1.1f, FontScale.scale, 0f)
    }

    @Test fun ketebalanSliderMulusDenganDefaultDiKiriTengah() {
        assertEquals(0f, FontTheme.adjToFraction(FontTheme.MIN_ADJ), 0.001f)
        assertEquals(FontTheme.DEFAULT_FRACTION, FontTheme.adjToFraction(0), 0.001f)
        assertEquals(1f, FontTheme.adjToFraction(FontTheme.MAX_ADJ), 0.001f)
        assertEquals(0, FontTheme.fractionToAdj(0.41f))
        assertEquals(FontTheme.MIN_ADJ, FontTheme.fractionToAdj(0f))
        assertEquals(FontTheme.MAX_ADJ, FontTheme.fractionToAdj(1f))
    }

    @Test fun ketebalanDiBatasiDalamRentang() {
        FontTheme.setWeight(999); assertEquals(FontTheme.MAX_ADJ, FontTheme.weight)
        assertTrue(FontTheme.isBold)
        FontTheme.setWeight(-999); assertEquals(FontTheme.MIN_ADJ, FontTheme.weight)
        FontTheme.setWeight(0); assertEquals(0, FontTheme.weight)
    }

    @Test fun warnaTeksMenggelapSaatTipis() {
        FontTheme.setWeight(0); assertEquals(0f, FontTheme.thinBoost, 0f)
        FontTheme.setWeight(FontTheme.MAX_ADJ); assertEquals(0f, FontTheme.thinBoost, 0f)
        FontTheme.setWeight(FontTheme.MIN_ADJ); assertEquals(1f, FontTheme.thinBoost, 0f)
        FontTheme.setWeight(-150); assertEquals(0.5f, FontTheme.thinBoost, 0.001f)
    }

    @Test fun temaMenimpaSkalaFontPerangkatDanActivityMengunciKonfigurasi() {
        val theme = File("src/main/java/com/mythos/app/ui/Theme.kt").readText()
        assertTrue(theme.contains("LocalDensity provides Density(base.density, FontScale.scale)"))
        val main = File("src/main/java/com/mythos/app/MainActivity.kt").readText()
        assertTrue(main.contains("cfg.fontScale = 1f"))
        assertTrue(main.contains("cfg.fontWeightAdjustment = 0"))
        assertTrue(main.contains("cfg.densityDpi = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE"))
    }
}
