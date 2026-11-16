package com.mythos.app.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/** patch31: salinan kerja proyek antara aplikasi dan Termux. Sisi runner diuji di jstest/codex_runner.test.js. */
class WorkspaceMirrorTest {
    private fun project(): File {
        val d = Files.createTempDirectory("mythos-ws").toFile()
        File(d, "a.txt").writeText("asli")
        File(d, "uploads/x").mkdirs()
        File(d, "uploads/x/ada.txt").writeText("lampiran")
        File(d, ".git").mkdirs()
        File(d, ".git/config").writeText("rahasia repo")
        File(d, "build").mkdirs()
        File(d, "build/out.bin").writeText("hasil build")
        File(d, ".env").writeText("API_TOKEN=secret")
        File(d, ".env.production").writeText("API_TOKEN=secret")
        File(d, "local.properties").writeText("storePassword=secret")
        File(d, "credentials.json").writeText("{\"private_key\":\"secret\"}")
        File(d, "server.pem").writeText("private key")
        File(d, ".codex").mkdirs()
        File(d, ".codex/auth.json").writeText("token")
        File(d, ".ssh").mkdirs()
        File(d, ".ssh/id_ed25519").writeText("private key")
        File(d, ".SSH").mkdirs()
        File(d, ".SSH/id_rsa").writeText("private key uppercase")
        return d
    }

    private fun paths(req: JSONObject): Set<String> =
        (0 until req.getJSONArray("files").length()).map { req.getJSONArray("files").getJSONObject(it).getString("path") }.toSet()

    @Test fun permintaanBerisiBerkasProyekTanpaFolderTerlarang() {
        val d = project()
        val req = WorkspaceMirror { d }.buildRequest()!!
        assertEquals(d.name, req.getString("id"))
        assertEquals(setOf("a.txt", "uploads/x/ada.txt"), paths(req))
        assertFalse(paths(req).any { WorkspaceFiles.isSensitivePath(it) })
    }

    @Test fun setelahCommitHanyaBerkasYangBerubahDikirimUlang() {
        val d = project()
        val m = WorkspaceMirror { d }
        m.buildRequest(); m.commitPush()
        assertEquals(emptySet<String>(), paths(m.buildRequest()!!))
        File(d, "a.txt").writeText("diubah di aplikasi")
        assertEquals(setOf("a.txt"), paths(m.buildRequest()!!))
    }

    @Test fun tanpaCommitSemuaBerkasDikirimLagi() {
        val d = project()
        val m = WorkspaceMirror { d }
        m.buildRequest()
        assertEquals(setOf("a.txt", "uploads/x/ada.txt"), paths(m.buildRequest()!!))
    }

    @Test fun tanpaProyekTidakAdaPermintaan() {
        assertEquals(null, WorkspaceMirror { null }.buildRequest())
    }

    @Test fun applyBackMenulisMengubahMenghapusDanMenolakPathBerbahaya() {
        val d = project()
        val m = WorkspaceMirror { d }
        val out = m.applyBack(
            mapOf(
                "a.txt" to "diubah codex".toByteArray(),
                "uploads/baru.txt" to "dibuat".toByteArray(),
                "../keluar.txt" to "jahat".toByteArray(),
                "/etc/x" to "jahat".toByteArray(),
                ".git/config" to "jahat".toByteArray(),
                ".env" to "API_TOKEN=malicious".toByteArray(),
                "config/credentials.json" to "{\"key\":1}".toByteArray(),
                "new-key.pem" to "private key".toByteArray(),
                "local.properties" to "secret".toByteArray(),
            ),
            listOf("uploads/x/ada.txt", "../lain.txt", ".codex/auth.json")
        )
        assertEquals("diubah codex", File(d, "a.txt").readText())
        assertEquals("dibuat", File(d, "uploads/baru.txt").readText())
        assertFalse(File(d, "uploads/x/ada.txt").exists())
        assertFalse(File(d.parentFile, "keluar.txt").exists())
        assertEquals("rahasia repo", File(d, ".git/config").readText())
        assertEquals("API_TOKEN=secret", File(d, ".env").readText())
        assertFalse(File(d, "new-key.pem").exists())
        assertFalse(File(d, "config/credentials.json").exists())
        assertEquals("storePassword=secret", File(d, "local.properties").readText())
        assertTrue(out, out.contains("2 berkas") && out.contains("1 dihapus") && out.contains("ditolak"))
    }

    @Test fun hasilKerjaCodexTidakDikirimUlangSebagaiBerubah() {
        val d = project()
        val m = WorkspaceMirror { d }
        m.buildRequest(); m.commitPush()
        m.applyBack(mapOf("a.txt" to "diubah codex".toByteArray()), emptyList())
        assertEquals(emptySet<String>(), paths(m.buildRequest()!!))
    }

    @Test fun aliranMenerimaBerkasDanPenghapusanDariRunner() {
        val st = CodexStreamState { }
        val b64 = Base64.getEncoder().encodeToString("isi".toByteArray())
        st.accept("""{"type":"file","path":"a.txt","b64":"$b64"}""")
        st.accept("""{"type":"files_done","deleted":["x.txt"],"skipped":["besar.bin"]}""")
        val (files, deleted) = st.takeWorkspaceChanges()
        assertEquals("isi", String(files["a.txt"]!!))
        assertEquals(listOf("x.txt"), deleted)
    }

    @Test fun promptWorkspaceMenyebutSalinanKerjaDanTidakMelarangShell() {
        val p = codexPrompt("sistem", listOf(Msg(Role.USER, "cek")), native = true, workspace = true)
        assertTrue(p.contains("DIREKTORI KERJA = salinan folder proyek"))
        assertFalse(p.contains("TIDAK bisa dibuka dari Termux"))
        val legacy = codexPrompt("sistem", listOf(Msg(Role.USER, "cek")), native = true)
        assertTrue(legacy.contains("TIDAK bisa dibuka dari Termux"))
    }
}
