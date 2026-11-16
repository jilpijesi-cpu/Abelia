package com.mythos.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

private class ProbeLlm(private val replies: List<String>) : LlmClient {
    var calls = 0
    val seen = mutableListOf<List<Msg>>()
    override suspend fun complete(system: String, messages: List<Msg>): String {
        seen.add(messages.toList())
        return replies[calls++]
    }
}

/** patch28: model yang mengaku "Read/Glob tidak tersedia" langsung dibuktikan salah: aplikasi menjalankan Glob pada folder uploads (semua berkas) dan mengirim hasilnya. */
class ToolClaimProbeTest {
    @Test fun klaimAlatTidakTersediaMemicuGlobUploadsOtomatisLaluModelMelanjutkan() = runTest {
        val dir = Files.createTempDirectory("mythos-probe").toFile()
        File(dir, "uploads").mkdirs()
        File(dir, "uploads/catatan.txt").writeText("isi lampiran")
        val llm = ProbeLlm(
            listOf(
                "Saya tidak dapat mengakses lampiran: alat Read dan Glob tidak tersedia di sesi ini.",
                "Lampiran terlihat: catatan.txt.",
            )
        )
        val ag = Agent(
            llm, ToolRegistry.default(false, withPlan = false), ProjectSandbox(dir),
            Approver { _, _ -> true }, { "" }, null, AgentConfig()
        )
        val res = ag.execute("cek lampiran") { }
        assertTrue(res.success)
        assertEquals("Lampiran terlihat: catatan.txt.", res.summary)
        assertEquals(2, llm.calls)
        val last = llm.seen[1].last()
        assertEquals(Role.USER, last.role)
        assertTrue("hasil Glob berisi berkas lampiran", last.text.contains("uploads/catatan.txt"))
        assertTrue("pengingat menjelaskan format blok", last.text.contains("NOT native functions"))
    }

    @Test fun koreksiHanyaSekaliJadiPenolakanKeduaTidakMengulangGlob() = runTest {
        val dir = Files.createTempDirectory("mythos-probe2").toFile()
        val llm = ProbeLlm(
            listOf(
                "Saya tidak bisa membaca lampiran.",
                "Saya tetap tidak bisa membaca lampiran itu.",
                "Saya tetap tidak bisa membaca lampiran itu.",
                "Saya tetap tidak bisa membaca lampiran itu.",
                "Saya tetap tidak bisa membaca lampiran itu.",
                "Saya tetap tidak bisa membaca lampiran itu.",
            )
        )
        val ag = Agent(
            llm, ToolRegistry.default(false, withPlan = false), ProjectSandbox(dir),
            Approver { _, _ -> true }, { "" }, null, AgentConfig(maxSteps = 6)
        )
        runCatching { ag.execute("cek lampiran") { } }
        val globResults = llm.seen.flatten().count { it.role == Role.USER && it.text.contains("Proof: the app just ran Glob") }
        assertTrue("probe Glob hanya dikirim sekali per tugas, tapi muncul di tiap riwayat berikutnya", globResults >= 1)
        assertEquals(1, llm.seen.last().count { it.text.contains("Proof: the app just ran Glob") })
    }
}
