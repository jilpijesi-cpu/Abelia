package com.mythos.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Alat pencatat sengketa & transaksi terstruktur (`CaseLedger`).
 * Membantu agen menangani kasus uang tertahan di e-commerce/fintech/bank, menghitung tenggat hari kerja (SLA),
 * mencatat linimasa klaim bertanggal beserta bukti, mendeteksi ketidakkonsistenan pernyataan CS,
 * dan memperbarui berkas `KASUS.md` secara rapi.
 */
class CaseLedger : Tool {
    override val name = "CaseLedger"
    override val risk = Risk.WRITE
    override val effect = Effect.WRITE

    override val doc = """CaseLedger {"action": "upsert_case"|"add_claim"|"calculate_sla"|"summary", ...} — structured dispute & transaction ledger synced with KASUS.md:
  - "upsert_case": {"action": "upsert_case", "platform": "<nama platform>", "ticket_id": "<nomor tiket>", "transaction_id": "<ID transaksi>", "amount_idr": <nominal, angka saja>, "status": "<status menurut dokumen>", "complainant": "<nama pengadu>", "official_channel": "<saluran resmi dari Playbook atau situs resmi>"} — fill a field ONLY with what the user wrote or a file you read; OMIT any field you do not know (the ledger then shows [belum diisi]). Values in <angle brackets> are format placeholders, never data.
  - "add_claim": {"action": "add_claim", "date": "<yyyy-MM-dd HH:mm>", "actor": "<siapa yang menyatakan>", "claim": "<isi klaim sesuai bukti>", "evidence": "<berkas bukti di uploads/>", "inconsistent_with": "<klaim lain yang bertentangan, bila ada>"}
  - "calculate_sla": {"action": "calculate_sla", "start_date": "<yyyy-MM-dd>", "business_days": <jumlah hari kerja dari aturan resmi>} — compute the exact business-day deadline (skipping weekends).
  - "summary": {"action": "summary"} — read current structured case summary and detected inconsistencies."""

    override fun describe(args: JSONObject): String =
        "CaseLedger (${args.str("action") ?: "summary"}: ${oneLine(args.str("platform") ?: args.str("claim") ?: args.str("start_date") ?: "", 80)})"

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val action = args.str("action")?.lowercase()?.trim().orEmpty().ifEmpty { "summary" }
        val jsonFile = env.sandbox.resolve(LEDGER_JSON)
        val kasusFile = env.sandbox.resolve("KASUS.md")

        return when (action) {
            "calculate_sla" -> {
                val startStr = args.reqStr("start_date").trim()
                val days = args.intOr("business_days", args.intOr("work_days", 14)).coerceIn(1, 180)
                val holidays = parseHolidays(args.opt("holidays"))
                val deadline = addBusinessDays(startStr, days, holidays)
                    ?: throw ArgError("'start_date' harus berformat yyyy-MM-dd, misalnya 2026-11-08")
                val state = loadLedger(jsonFile)
                state.put("deadline", deadline)
                saveLedgerAndSyncMarkdown(env, jsonFile, kasusFile, state)
                ToolResult(
                    true,
                    "Perhitungan SLA: $days hari kerja (Sabtu & Minggu dilewati" +
                        (if (holidays.isEmpty()) "; hari libur nasional/cuti bersama TIDAK dihitung" else "; ${holidays.size} tanggal libur dilewati") +
                        ") sejak $startStr jatuh pada **$deadline 17:00** (waktu lokal). " +
                        (if (holidays.isEmpty()) "PERINGATAN: tanpa daftar libur, tanggal ini bisa lebih awal dari tenggat resmi; hitung ulang dengan \"holidays\" dari SKB libur nasional atau konfirmasi ke penyelenggara sebelum dipakai sebagai tenggat hukum. " else "") +
                        "Gunakan tanggal ini pada SetDeadlineNotice atau Google Calendar."
                )
            }
            "upsert_case" -> {
                val state = loadLedger(jsonFile)
                (args.str("platform") ?: args.str("provider"))?.takeIf { isRealValue(it) }?.let { state.put("platform", it.trim()) }
                (args.str("ticket_id") ?: args.str("ticket_number"))?.takeIf { isRealValue(it) }?.let { state.put("ticket_id", it.trim()) }
                args.str("transaction_id")?.takeIf { isRealValue(it) }?.let { state.put("transaction_id", it.trim()) }
                // patch16: nominal yang tidak valid (penanda, teks bebas, 0) tidak boleh menimpa nominal yang sudah tersimpan.
                if (args.has("amount_idr") && !args.isNull("amount_idr")) {
                    parseAmount(args.opt("amount_idr"))?.let { state.put("amount_idr", it) }
                }
                args.str("status")?.takeIf { isRealValue(it) }?.let { state.put("status", it.trim()) }
                args.str("complainant")?.takeIf { isRealValue(it) }?.let { state.put("complainant", it.trim()) }
                (args.str("official_channel") ?: args.str("regulator"))?.takeIf { isRealValue(it) }?.let { state.put("official_channel", it.trim()) }
                args.str("deadline")?.takeIf { isRealValue(it) }?.let { state.put("deadline", it.trim()) }
                val changed = saveLedgerAndSyncMarkdown(env, jsonFile, kasusFile, state)
                ToolResult(
                    true,
                    "Data pokok sengketa diperbarui dan disinkronkan ke KASUS.md:\n" + renderSummary(state),
                    changed = changed,
                    readPaths = changed
                )
            }
            "add_claim" -> {
                val date = args.reqStr("date").trim()
                if (!isRealValue(date)) throw ArgError("'date' berisi penanda atau kosong; isi dengan tanggal dari pengguna/berkas, atau tanyakan dulu")
                val actor = (args.str("actor") ?: args.str("party"))?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw ArgError("argumen 'actor' wajib diisi")
                if (!isRealValue(actor)) throw ArgError("'actor' berisi penanda atau kosong; isi dengan pihak yang sebenarnya menyatakan, atau tanyakan dulu")
                val claim = args.reqStr("claim").trim()
                if (claim.isEmpty()) throw ArgError("'claim' tidak boleh kosong")
                if (!isRealValue(claim)) throw ArgError("'claim' berisi penanda; tulis klaim sesuai bukti, atau tanyakan dulu")
                // Bukti dan ketidakkonsistenan bersifat opsional: penanda dibuang menjadi kosong, bukan disimpan sebagai fakta.
                val evidence = (args.str("evidence") ?: args.str("evidence_file"))?.trim()?.takeIf { isRealValue(it) }.orEmpty()
                val inconsistent = (args.str("inconsistent_with") ?: args.str("contradiction"))?.trim()?.takeIf { isRealValue(it) }.orEmpty()

                val state = loadLedger(jsonFile)
                val claims = state.optJSONArray("claims") ?: JSONArray().also { state.put("claims", it) }
                claims.put(
                    JSONObject()
                        .put("date", date)
                        .put("actor", actor)
                        .put("claim", claim)
                        .put("evidence", evidence)
                        .put("inconsistent_with", inconsistent)
                )
                val changed = saveLedgerAndSyncMarkdown(env, jsonFile, kasusFile, state)
                ToolResult(
                    true,
                    "Klaim/fakta tanggal $date ($actor) tercatat di KASUS.md (total ${claims.length()} entri linimasa).",
                    changed = changed,
                    readPaths = changed
                )
            }
            "summary" -> {
                val state = loadLedger(jsonFile)
                val md = if (kasusFile.isFile) kasusFile.readText().take(4_000) else "(KASUS.md belum dibuat)"
                ToolResult(true, renderSummary(state) + "\n\n--- Isi KASUS.md ---\n" + md)
            }
            else -> throw ArgError("'action' tidak dikenal: $action (gunakan upsert_case, add_claim, calculate_sla, atau summary)")
        }
    }

    companion object {
        const val LEDGER_JSON = "kasus_ledger.json"

        /** Nilai kosong, penanda `<...>` dari contoh alat, atau "[belum diisi]" bukan fakta kasus dan tidak boleh disimpan. */
        internal fun isRealValue(v: String): Boolean {
            val t = v.trim()
            if (t.isEmpty()) return false
            if (t.startsWith("<") && t.endsWith(">")) return false
            return !t.equals("[belum diisi]", ignoreCase = true)
        }

        /**
         * patch16: mengubah nominal menjadi Long, atau null bila tidak bisa dipastikan.
         * Menerima angka JSON, atau teks seperti "2500000", "2.500.000", "Rp 2.500.000".
         * Menolak penanda `<...>`, teks bebas ("2,5 juta"), dan nilai <= 0 supaya nominal yang sudah ada tidak tertimpa.
         */
        internal fun parseAmount(raw: Any?): Long? {
            val v: Long? = when (raw) {
                null -> null
                is Int -> raw.toLong()
                is Long -> raw
                is Number -> raw.toDouble().takeIf { it.isFinite() && it == Math.floor(it) }?.toLong()
                is String -> {
                    val t = raw.trim().replace(Regex("""^(?i)rp\.?\s*"""), "").trim()
                    when {
                        Regex("""^\d{1,15}$""").matches(t) -> t.toLongOrNull()
                        Regex("""^\d{1,3}([.,]\d{3}){1,4}$""").matches(t) -> t.replace(Regex("""[.,]"""), "").toLongOrNull()
                        else -> null
                    }
                }
                else -> null
            }
            return v?.takeIf { it > 0 }
        }

        /** Terima array JSON atau teks dipisah koma/spasi; hanya yyyy-MM-dd yang valid dipakai. */
        internal fun parseHolidays(raw: Any?): Set<String> {
            val items = when (raw) {
                null, JSONObject.NULL -> emptyList()
                is org.json.JSONArray -> (0 until raw.length()).map { raw.optString(it) }
                else -> raw.toString().split(',', ';', ' ', '\n')
            }
            return items.map { it.trim() }.filter { Regex("""^\d{4}-\d{2}-\d{2}$""").matches(it) }.toSet()
        }

        internal fun addBusinessDays(startYmd: String, businessDays: Int, holidays: Set<String> = emptySet()): String? {
            val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(startYmd.trim()) ?: return null
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply {
                isLenient = false
                clear()
                set(y, mo - 1, d, 9, 0, 0)
            }
            try {
                cal.timeInMillis
            } catch (_: IllegalArgumentException) {
                return null
            }
            var remaining = businessDays
            while (remaining > 0) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = cal.timeZone }.format(cal.time)
                if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY && ymd !in holidays) {
                    remaining--
                }
            }
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = cal.timeZone }.format(cal.time)
        }

        private fun loadLedger(f: File): JSONObject =
            if (f.isFile) try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
            else JSONObject()

        private fun saveLedgerAndSyncMarkdown(
            env: ToolEnv,
            jsonFile: File,
            kasusFile: File,
            state: JSONObject,
        ): List<String> {
            val changed = ArrayList<String>()
            val jsonRel = env.sandbox.relative(jsonFile)
            env.journal?.beforeChange(jsonFile)
            val jsonText = state.toString(2)
            jsonFile.writeText(jsonText)
            env.session.known[jsonRel] = jsonText.hashCode()
            changed.add(jsonRel)

            // Sinkronkan bagian terstruktur ke KASUS.md tanpa menghapus catatan manual di luar penanda
            val kasusRel = env.sandbox.relative(kasusFile)
            env.journal?.beforeChange(kasusFile)
            val block = renderMarkdownBlock(state)
            val existing = if (kasusFile.isFile) kasusFile.readText() else ""
            val sIdx = existing.indexOf(BEGIN_MARKER)
            val eIdx = if (sIdx >= 0) existing.indexOf(END_MARKER, sIdx + BEGIN_MARKER.length) else -1
            val updated = if (sIdx >= 0 && eIdx >= 0) {
                existing.substring(0, sIdx) + block + existing.substring(eIdx + END_MARKER.length)
            } else if (existing.isBlank()) {
                block + "\n"
            } else {
                block + "\n\n" + existing.trim() + "\n"
            }
            kasusFile.writeText(updated)
            env.session.known[kasusRel] = updated.hashCode()
            changed.add(kasusRel)
            return changed
        }

        private const val BEGIN_MARKER = "<!-- BEGIN_CASE_LEDGER -->"
        private const val END_MARKER = "<!-- END_CASE_LEDGER -->"

        internal fun renderSummary(state: JSONObject): String = buildString {
            val platform = state.optString("platform", "-")
            val ticket = state.optString("ticket_id", "-")
            val trx = state.optString("transaction_id", "-")
            val amount = state.optLong("amount_idr", 0L)
            val status = state.optString("status", "-")
            val complainant = state.optString("complainant", "-")
            val channel = state.optString("official_channel", "-")
            val deadline = state.optString("deadline", "-")
            append("Platform/Pihak: $platform | Tiket: $ticket | Transaksi: $trx\n")
            if (amount > 0) append("Nominal Tertahan/Sengketa: Rp %,d\n".format(Locale.US, amount).replace(',', '.'))
            append("Pengadu: $complainant | Status: $status | Kanal Resmi: $channel | Tenggat: $deadline")
            val claims = state.optJSONArray("claims") ?: JSONArray()
            val inconsistencies = (0 until claims.length()).mapNotNull { claims.optJSONObject(it) }
                .filter { it.optString("inconsistent_with").isNotBlank() }
            if (inconsistencies.isNotEmpty()) {
                append("\nKetidakkonsistenan terdeteksi: ${inconsistencies.size} butir")
            }
        }

        private fun renderMarkdownBlock(state: JSONObject): String = buildString {
            appendLine(BEGIN_MARKER)
            appendLine("# Ringkasan Kasus & Transaksi")
            appendLine("- **Platform / Penyelenggara**: ${state.optString("platform", "[belum diisi]")}")
            appendLine("- **Nomor Tiket / Pengaduan**: ${state.optString("ticket_id", "[belum diisi]")}")
            appendLine("- **ID Transaksi / Invoice**: ${state.optString("transaction_id", "[belum diisi]")}")
            val amt = state.optLong("amount_idr", 0L)
            if (amt > 0) {
                appendLine("- **Nominal Sengketa / Dana Tertahan**: Rp ${"%,d".format(Locale.US, amt).replace(',', '.')}")
            }
            appendLine("- **Nama Pengadu**: ${state.optString("complainant", "[belum diisi]")}")
            appendLine("- **Saluran Resmi Tujuan**: ${state.optString("official_channel", "[belum diverifikasi]")}")
            appendLine("- **Status & Tenggat SLA**: ${state.optString("status", "[belum diisi]")} (Tenggat: ${state.optString("deadline", "-")})")
            val claims = state.optJSONArray("claims") ?: JSONArray()
            if (claims.length() > 0) {
                appendLine()
                appendLine("## Linimasa Klaim, Fakta & Bukti")
                for (i in 0 until claims.length()) {
                    val c = claims.optJSONObject(i) ?: continue
                    val ev = c.optString("evidence").let { if (it.isNotBlank()) " *(Bukti: `$it`)*" else "" }
                    val inc = c.optString("inconsistent_with").let {
                        if (it.isNotBlank()) " — **[Tidak konsisten dengan: $it]**" else ""
                    }
                    appendLine("${i + 1}. **[${c.optString("date")}] ${c.optString("actor")}**: ${c.optString("claim")}$ev$inc")
                }
            }
            append(END_MARKER)
        }
    }
}
