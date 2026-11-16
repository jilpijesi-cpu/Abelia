package com.mythos.app.agent

/**
 * Penjelasan berbahasa sehari-hari untuk kartu "Izinkan aksi ini?".
 * Perintah mentah TIDAK dibuang: tetap tersedia di [technical] (ditampilkan lewat tombol "Lihat detail"),
 * sehingga pengguna selalu bisa memeriksa teks lengkap yang benar-benar akan dijalankan.
 */
internal data class ApprovalExplanation(
    /** Kalimat pembuka: siapa meminta apa. */
    val intro: String,
    /** Apa yang dilakukan, satu baris per langkah. */
    val steps: List<String>,
    /** Dampaknya bagi ponsel/berkas pengguna. */
    val impact: List<String>,
    /** Teks asli (perintah lengkap) untuk diperiksa. */
    val technical: String,
    /** Peringatan tambahan dari agen (mis. konteks memuat konten eksternal). */
    val note: String? = null,
)

private const val WARN_MARK = "\n\nKonteks memuat"

/** Kembalikan penjelasan untuk [tool] bila dikenali; null = tampilkan teks mentah seperti biasa. */
internal fun explainApproval(tool: String, summary: String): ApprovalExplanation? = try {
    when (tool) {
        "Write", "Edit", "Delete", "WebFetch" -> explainSimple(tool, summary)
        else -> null
    }
} catch (e: Exception) {
    null // penjelas tidak boleh pernah menghalangi kartu persetujuan
}

private fun splitWarning(summary: String): Pair<String, String?> {
    val i = summary.indexOf(WARN_MARK)
    return if (i < 0) summary to null else summary.substring(0, i) to summary.substring(i).trim()
}

private fun explainSimple(tool: String, summary: String): ApprovalExplanation? {
    val (body, warn) = splitWarning(summary)
    val line = body.trim()
    return when (tool) {
        "Write" -> {
            val m = Regex("""^Write (.+) \((\d+) karakter\)$""", RegexOption.DOT_MATCHES_ALL).find(line) ?: return null
            ApprovalExplanation(
                "Mythos ingin membuat atau menimpa sebuah berkas.",
                listOf("Berkas: ${m.groupValues[1]} (±${m.groupValues[2]} karakter)"),
                listOf("Jika berkas dengan nama ini sudah ada, isinya akan diganti."),
                summary, warn,
            )
        }
        "Edit" -> {
            val m = Regex("""^Edit (.+)$""", RegexOption.DOT_MATCHES_ALL).find(line) ?: return null
            ApprovalExplanation(
                "Mythos ingin mengubah sebagian isi sebuah berkas.",
                listOf("Berkas: ${m.groupValues[1]}"),
                listOf("Isi berkas ini akan berubah."),
                summary, warn,
            )
        }
        "Delete" -> {
            val m = Regex("""^Delete (.+)$""", RegexOption.DOT_MATCHES_ALL).find(line) ?: return null
            ApprovalExplanation(
                "Mythos ingin menghapus sebuah berkas.",
                listOf("Berkas: ${m.groupValues[1]}"),
                listOf("Perhatian: berkas yang dihapus mungkin tidak bisa dikembalikan."),
                summary, warn,
            )
        }
        else -> { // WebFetch
            val m = Regex("""^WebFetch (\S+)""").find(line) ?: return null
            ApprovalExplanation(
                "Mythos ingin membuka sebuah halaman web dan membaca isinya.",
                listOf("Alamat: ${m.groupValues[1]}"),
                listOf("Akan memakai internet."),
                summary, warn,
            )
        }
    }
}

/** Jalur panjang dipersingkat dari kiri agar muat satu-dua baris. */
private fun shortPath(p: String): String = p.trim().let { if (it.length > 48) "…" + it.takeLast(47) else it }

/** Satu baris pendek untuk kartu konfirmasi ringkas (PATCH36). Teks lengkap tetap ada di "Detail". */
internal fun shortApproval(tool: String, summary: String): String {
    val line = splitWarning(summary).first.trim().lineSequence().firstOrNull().orEmpty()
    val text = when (tool) {
        "WebFetch" -> Regex("""^WebFetch (\S+)""").find(line)?.let { m ->
            "Buka " + m.groupValues[1].removePrefix("https://").removePrefix("http://").removePrefix("www.").take(60)
        }
        "Write" -> Regex("""^Write (.+) \(\d+ karakter\)$""").find(line)?.let { "Tulis ${shortPath(it.groupValues[1])}" }
        "Edit" -> Regex("""^Edit (.+)$""").find(line)?.let { "Ubah ${shortPath(it.groupValues[1])}" }
        "Delete" -> Regex("""^Delete (.+)$""").find(line)?.let { "Hapus ${shortPath(it.groupValues[1])}" }
        "GmailDraftSend" -> Regex("""\[(\w+)\] Kepada: ([^|]*)""").find(line)?.let { m ->
            val to = m.groupValues[2].trim().take(40)
            if (m.groupValues[1].equals("send", true)) "Kirim email ke $to" else "Buat draf email ke $to"
        }
        else -> null
    }
    return text ?: "$tool: ${line.take(70)}"
}

/** true bila agen menempelkan peringatan "konteks memuat konten eksternal" pada ringkasan. */
internal fun hasExternalWarning(summary: String): Boolean = summary.contains(WARN_MARK)
