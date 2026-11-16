package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptGuardTest {
    @Test fun hurufCjkNyasarDibuangDariJawabanIndonesia() {
        assertEquals("lebih terstruktur dan.", ScriptGuard.strip("lebih terstruktur dan\u53EF\u89E3\u91CA.", "jelaskan kenapa Claude bagus"))
        assertEquals("Hasil bagus, ya.", ScriptGuard.strip("Hasil \u597D bagus, ya.", "halo"))
    }

    @Test fun penggunaYangMemakaiCjkTidakDisentuh() {
        val a = "Kata \u4F60\u597D artinya halo."
        assertEquals(a, ScriptGuard.strip(a, "apa arti \u4F60\u597D?"))
    }

    @Test fun blokKodeDanKodeSatuBarisDibiarkan() {
        val a = "Contoh `\u4F60\u597D` dan:\n```\nprintln(\"\u4F60\u597D\")\n```\nSelesai."
        assertEquals(a, ScriptGuard.strip(a, "tampilkan contoh"))
    }

    @Test fun jawabanTanpaCjkTidakBerubah() {
        val a = "Jawaban biasa, tanpa huruf asing."
        assertEquals(a, ScriptGuard.strip(a, "x"))
        assertTrue(!ScriptGuard.hasCjk(a))
    }

    @Test fun promptMemuatAturanBekerjaSepertiAhliCermat() {
        val p = Protocol.systemPrompt(ToolRegistry.default(false), false)
        listOf("FOLLOW INSTRUCTIONS EXACTLY", "THINK BEFORE CONCLUDING", "BE CALIBRATED", "LARGE CODE WORK", "ONE LANGUAGE", "STAY ON COURSE")
            .forEach { assertTrue(it, p.contains(it)) }
    }
}
