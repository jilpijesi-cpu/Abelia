package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodexImagesTest {
    private fun img(n: Int) = ImagePart("image/jpeg", "AAAA$n")

    @Test fun hanyaGambarDariPesanTerakhirYangMembawaGambarDikirim() {
        val msgs = listOf(
            Msg(Role.USER, "lama", listOf(img(1))),
            Msg(Role.ASSISTANT, "ok"),
            Msg(Role.USER, "baru", listOf(img(2), img(3))),
            Msg(Role.USER, "tanpa gambar"),
        )
        assertEquals(listOf("AAAA2", "AAAA3"), codexImages(msgs).map { it.base64 })
    }

    @Test fun tanpaGambarKosongDanJumlahDibatasi() {
        assertTrue(codexImages(listOf(Msg(Role.USER, "halo"))).isEmpty())
        val many = Msg(Role.USER, "banyak", (1..20).map { img(it) })
        assertEquals(CODEX_MAX_IMAGES, codexImages(listOf(many)).size)
    }

    @Test fun promptMemberitahuModelBahwaGambarTerlampir() {
        val p = codexPrompt("sys", listOf(Msg(Role.USER, "lihat ini", listOf(img(1)))), native = true)
        assertTrue(p.contains("1 gambar terlampir langsung"))
        val none = codexPrompt("sys", listOf(Msg(Role.USER, "halo")), native = true)
        assertTrue(!none.contains("gambar terlampir langsung"))
    }

    @Test fun runnerMeneruskanGambarSebagaiInputImage() {
        val js = File("src/main/assets/codex_app_server.js").readText()
        assertTrue(js.contains("type: 'image', url"))
        assertTrue(js.contains("input: buildInput("))
        assertTrue("fallback jujur bila Codex menolak gambar", js.contains("imagesDropped = true"))
    }

    @Test fun codexTidakDibungkusVisionGateKarenaNamaModelnyaTakDikenal() {
        // Regresi: VisionGate menilai "codex-cli" tak bisa melihat gambar lalu membuangnya sebelum sampai ke Codex.
        assertTrue(!supportsVision(ProviderKind.OPENAI_COMPATIBLE, "codex-cli"))
        val vm = File("src/main/java/com/mythos/app/ui/AgentViewModel.kt").readText()
        val codexBlock = vm.substringAfter("if (s.provider == ProviderChoice.CODEX) {").substringBefore("val locked = s.provider.lockedModel")
        assertTrue(!codexBlock.contains("VisionGate("))
        assertTrue(!codexBlock.contains("warnIfNoVision(false)"))
    }
}

class CodexActivityTest {
    @Test fun penandaAktivitasDariRunnerDiteruskan() {
        val got = ArrayList<CodexActivity>()
        val st = CodexStreamState { }
        st.onActivity = { got += it }
        st.accept("""{"n":1,"type":"activity","kind":"thinking","phase":"start","command":""}""")
        st.accept("""{"n":2,"type":"activity","kind":"command","phase":"start","command":"pkg list-installed"}""")
        st.accept("""{"n":3,"type":"activity","kind":"command","phase":"end","command":"pkg list-installed"}""")
        st.accept("""{"n":3,"type":"activity","kind":"command","phase":"end","command":"pkg list-installed"}""") // ganda: dibuang gerbang nomor urut
        st.accept("""{"n":4,"type":"activity","kind":"aneh","phase":"start","command":""}""")
        assertEquals(listOf("thinking", "command", "command"), got.map { it.kind })
        assertEquals(listOf(true, true, false), got.map { it.started })
        assertEquals("pkg list-installed", got[1].detail)
    }

    @Test fun runnerMemancarkanAktivitasPerintahHanyaDiModeNative() {
        val js = File("src/main/assets/codex_app_server.js").readText()
        assertTrue(js.contains("NATIVE && (itType === 'commandExecution'"))
        assertTrue(js.contains("type: 'activity', kind: 'thinking'"))
    }
}
