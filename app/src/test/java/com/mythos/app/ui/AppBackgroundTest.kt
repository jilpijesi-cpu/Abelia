package com.mythos.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackgroundTest {
    @After fun reset() = AppBackground.select("default")

    @Test fun bawaanSamaDenganLatarLama() {
        AppBackground.select("default")
        assertEquals(MythosColors.Background, AppBackground.topColor)
        assertEquals(MythosColors.Background, AppBackground.bottomColor)
    }

    @Test fun memilihLangitBiruMengubahWarnaTepi() {
        AppBackground.select("langit_biru")
        assertEquals("langit_biru", AppBackground.key)
        assertEquals(BgKind.IMAGE, AppBackground.option.kind)
        assertEquals(Color(0xFFFEFEFE), AppBackground.topColor)
        assertEquals(Color(0xFFADD8FD), AppBackground.bottomColor)
    }

    @Test fun kunciTakDikenalKembaliKeBawaan() {
        AppBackground.select("tidak-ada")
        assertEquals("default", AppBackground.key)
    }

    @Test fun fotoSendiriTanpaFileKembaliKeBawaan() {
        AppBackground.select(AppBackground.KEY_CUSTOM)
        assertEquals("default", AppBackground.key)
    }

    @Test fun kunciUnikDanBawaanPertama() {
        assertEquals("default", AppBackground.options.first().key)
        assertEquals(AppBackground.options.size, AppBackground.options.map { it.key }.toSet().size)
        assertTrue(AppBackground.options.none { it.key == AppBackground.KEY_CUSTOM })
    }
}
