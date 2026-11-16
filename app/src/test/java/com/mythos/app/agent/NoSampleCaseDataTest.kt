package com.mythos.app.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * patch15/16: deskripsi alat dibaca model sebagai contoh dan pernah disalin menjadi "fakta kasus" (tiket, nominal, status).
 * Contoh di deskripsi harus berupa penanda `<...>`, bukan data yang tampak nyata, dan prompt harus melarang mengarang fakta kasus.
 */
class NoSampleCaseDataTest {
    private val banned = listOf("730470", "2500000", "Menunggu SLA", "Dana tertahan", "Nama Pengadu", "lapor@flip.id", "bicara@bi.go.id", "Kasus Flip")

    private val docs: Map<String, String> = mapOf(
        "CaseLedger" to CaseLedger().doc,
        "ComposeEmail" to ComposeEmail().doc,
        "SetReminder" to SetReminder().doc,
        "SetDeadlineNotice" to SetDeadlineNotice().doc,
        "GmailDraftSend" to GmailDraftSend().doc,
        "GoogleDriveCaseVault" to GoogleDriveCaseVault().doc,
    )

    @Test
    fun toolDocsContainNoRealLookingCaseData() {
        for ((name, doc) in docs) {
            for (b in banned) assertFalse("$name masih memuat contoh data: $b", doc.contains(b))
        }
    }

    @Test
    fun toolDocsUsePlaceholdersInstead() {
        assertTrue(CaseLedger().doc.contains("<nomor tiket>"))
        assertTrue(ComposeEmail().doc.contains("<email penerima>"))
        assertTrue(SetDeadlineNotice().doc.contains("<yyyy-MM-dd HH:mm>"))
    }

    @Test
    fun systemPromptForbidsInventingCaseFacts() {
        // patch16: sebelumnya variabel loop masuk ke parameter canBuild; sekarang includeCases diuji dengan nama argumen.
        for (includeCases in listOf(false, true)) {
            val p = Protocol.systemPrompt(ToolRegistry.default(false), canBuild = false, includeCases = includeCases)
            assertTrue(p.contains("CASE FACTS"))
            assertTrue(p.contains("[belum diisi]"))
            assertTrue(p.contains("never copy them into a complaint"))
        }
    }

    @Test
    fun systemPromptHasNoRealLookingCaseExamples() {
        for (includeCases in listOf(false, true)) {
            val p = Protocol.systemPrompt(ToolRegistry.default(false), canBuild = false, includeCases = includeCases)
            for (b in listOf("batas Flip", "Flip, chat 11.58", "730470", "2025-06-11")) {
                assertFalse("prompt (includeCases=$includeCases) masih memuat contoh: $b", p.contains(b))
            }
        }
    }

    @Test
    fun toolDocsDoNotUseFlipAsSample() {
        for ((name, doc) in docs) assertFalse("$name masih memuat nama platform contoh", doc.contains("Flip"))
    }

    @Test
    fun isRealValueRejectsBlankPlaceholderAndEmptyMarker() {
        assertFalse(CaseLedger.isRealValue(""))
        assertFalse(CaseLedger.isRealValue("   "))
        assertFalse(CaseLedger.isRealValue("<nomor tiket>"))
        assertFalse(CaseLedger.isRealValue(" <nominal> "))
        assertFalse(CaseLedger.isRealValue("[belum diisi]"))
        assertTrue(CaseLedger.isRealValue("TKP-9988"))
        assertTrue(CaseLedger.isRealValue("Tokopedia"))
    }

    @Test
    fun ledgerIgnoresCopiedPlaceholdersAndShowsMissingFieldsAsBelumDiisi() = runBlocking {
        val sb = ProjectSandbox(Files.createTempDirectory("mythos-patch15").toFile())
        val env = ToolEnv(sb, SessionState(), { "" }, null)
        val r = CaseLedger().run(
            JSONObject()
                .put("action", "upsert_case")
                .put("platform", "Tokopedia")
                .put("ticket_id", "<nomor tiket>")
                .put("status", "[belum diisi]"),
            env
        )
        assertTrue(r.ok)
        val md = File(sb.root, "KASUS.md").readText()
        assertTrue(md.contains("Tokopedia"))
        assertFalse(md.contains("<nomor tiket>"))
        assertTrue(md.contains("**Nomor Tiket / Pengaduan**: [belum diisi]"))
        assertTrue(md.contains("**Status & Tenggat SLA**: [belum diisi]"))
        assertEquals(false, md.contains("Dalam proses"))
    }

    private fun newEnv(): Pair<ProjectSandbox, ToolEnv> {
        val sb = ProjectSandbox(Files.createTempDirectory("mythos-patch16").toFile())
        return sb to ToolEnv(sb, SessionState(), { "" }, null)
    }

    @Test
    fun parseAmountAcceptsRealNumbersAndRejectsEverythingElse() {
        assertEquals(2500000L, CaseLedger.parseAmount(2500000))
        assertEquals(2500000L, CaseLedger.parseAmount(2500000L))
        assertEquals(2500000L, CaseLedger.parseAmount("2500000"))
        assertEquals(2500000L, CaseLedger.parseAmount("2.500.000"))
        assertEquals(1500000L, CaseLedger.parseAmount("Rp 1.500.000"))
        assertEquals(null, CaseLedger.parseAmount("<nominal>"))
        assertEquals(null, CaseLedger.parseAmount("<nominal, angka saja>"))
        assertEquals(null, CaseLedger.parseAmount("2,5 juta"))
        assertEquals(null, CaseLedger.parseAmount("[belum diisi]"))
        assertEquals(null, CaseLedger.parseAmount(""))
        assertEquals(null, CaseLedger.parseAmount(0))
        assertEquals(null, CaseLedger.parseAmount(-5))
        assertEquals(null, CaseLedger.parseAmount(null))
        assertEquals(null, CaseLedger.parseAmount(JSONObject.NULL))
    }

    @Test
    fun placeholderAmountDoesNotOverwriteStoredAmount() = runBlocking {
        val (sb, env) = newEnv()
        val tool = CaseLedger()
        tool.run(JSONObject().put("action", "upsert_case").put("platform", "Tokopedia").put("amount_idr", 1500000), env)
        val r = tool.run(JSONObject().put("action", "upsert_case").put("amount_idr", "<nominal>"), env)
        assertTrue(r.ok)
        tool.run(JSONObject().put("action", "upsert_case").put("amount_idr", 0), env)
        val md = File(sb.root, "KASUS.md").readText()
        assertTrue(md.contains("Rp 1.500.000"))
    }

    @Test
    fun addClaimRejectsPlaceholderCoreFieldsAndDropsPlaceholderOptionalFields() = runBlocking {
        val (sb, env) = newEnv()
        val tool = CaseLedger()
        fun claim(date: String, actor: String, claim: String) = JSONObject()
            .put("action", "add_claim").put("date", date).put("actor", actor).put("claim", claim)

        for (bad in listOf(
            claim("<yyyy-MM-dd HH:mm>", "CS Tokopedia", "Dana cair 2x24 jam"),
            claim("2026-03-02 10:15", "<siapa yang menyatakan>", "Dana cair 2x24 jam"),
            claim("2026-03-02 10:15", "CS Tokopedia", "<isi klaim sesuai bukti>"),
            claim("2026-03-02 10:15", "[belum diisi]", "Dana cair 2x24 jam"),
        )) {
            val threw = try { tool.run(bad, env); false } catch (e: ArgError) { true }
            assertTrue("add_claim dengan penanda harus ditolak: $bad", threw)
        }
        assertFalse(File(sb.root, "KASUS.md").exists() && File(sb.root, "KASUS.md").readText().contains("<"))

        val ok = tool.run(
            claim("2026-03-02 10:15", "CS Tokopedia", "Dana cair 2x24 jam")
                .put("evidence", "<berkas bukti di uploads/>")
                .put("inconsistent_with", "<klaim lain yang bertentangan, bila ada>"),
            env
        )
        assertTrue(ok.ok)
        val md = File(sb.root, "KASUS.md").readText()
        assertTrue(md.contains("Dana cair 2x24 jam"))
        assertFalse(md.contains("<berkas bukti"))
        assertFalse(md.contains("<klaim lain"))
    }
}
