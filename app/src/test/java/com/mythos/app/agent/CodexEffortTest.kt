package com.mythos.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodexEffortTest {
    private val token = "a".repeat(64)

    @Test fun upayaBerpikirMasukSebagaiVariabelLingkunganRunner() {
        val cmd = TermuxLauncher.buildAppServerCommand("x", 49152, token, "halo", null, native = true, effort = "low")
        assertTrue(cmd.contains("MYTHOS_CODEX_NATIVE=1 MYTHOS_CODEX_EFFORT=low node"))
        val plain = TermuxLauncher.buildAppServerCommand("x", 49152, token, "halo", null, effort = "high")
        assertTrue(plain.contains("MYTHOS_CODEX_EFFORT=high node"))
    }

    @Test fun tanpaUpayaTidakAdaVariabelDanNilaiAsingDitolak() {
        assertFalse(TermuxLauncher.buildAppServerCommand("x", 49152, token, "halo").contains("MYTHOS_CODEX_EFFORT"))
        for (bad in listOf("low; rm -rf ~", "ultra", "", "LOW", "low node")) {
            assertFalse(bad, runCatching { TermuxLauncher.buildAppServerCommand("x", 49152, token, "halo", effort = bad) }.isSuccess)
        }
    }

    @Test fun runnerMemakaiDaftarPutihDanMengulangBilaDitolak() {
        val js = File("src/main/assets/codex_app_server.js").readText()
        assertTrue(js.contains("['low', 'medium', 'high'].includes(process.env.MYTHOS_CODEX_EFFORT"))
        assertTrue(js.contains("p.effort = EFFORT"))
        assertTrue(js.contains("effortDropped = true"))
        assertTrue(js.contains("approvalPolicy: 'on-request'"))
    }
}
