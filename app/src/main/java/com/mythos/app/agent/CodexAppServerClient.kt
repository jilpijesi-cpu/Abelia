package com.mythos.app.agent

import android.content.Context

/**
 * Adapter provider Codex. Mode native (bawaan ON, PATCH33): Codex menjalankan shell Termux dengan akses penuh (never + danger-full-access), tanpa dialog persetujuan. Mode model-saja
 * (sakelar native dimatikan): Mythos mengelola loop dan eksekusi tool sendiri, dan Codex app-server hanya menghasilkan completion.
 */
class CodexAppServerClient(
    private val context: Context,
    /** Mode Codex native dengan tool bawaan dan akses penuh ke Termux; tidak ada dialog persetujuan. */
    private val native: Boolean = false,
    /** Penanda "Codex sedang berpikir / menjalankan perintah" untuk baris aktivitas hidup di UI. */
    private val onActivity: ((CodexActivity) -> Unit)? = null,
    /** Upaya berpikir (low/medium/high); null = ikuti konfigurasi Codex. */
    private val effort: String? = null,
    /** Salinan proyek yang dibatasi ke folder kerja (hanya mode native); null = sesi scratch sementara. */
    private val workspace: WorkspaceMirror? = null,
) : LlmClient {
    /** Selalu satu sesi per pesan; jalur always-on sudah dihapus (v83). */
    private suspend fun request(prompt: String, images: List<ImagePart>, onText: (String) -> Unit): String =
        TermuxLauncher.runCodexAppServer(context, prompt, onText, native, images, onActivity, effort?.takeIf { it in TermuxLauncher.CODEX_EFFORTS }, workspace)

    override suspend fun complete(system: String, messages: List<Msg>): String =
        request(codexPrompt(system, messages, native, hasWorkspace()), codexImages(messages)) {}

    override suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String =
        request(codexPrompt(system, messages, native, hasWorkspace()), codexImages(messages), onText)

    private fun hasWorkspace(): Boolean = native && runCatching { workspace?.workspaceId() != null }.getOrDefault(false)
}

private val STRUCTURAL_TAG = Regex("<(/?)(MYTHOS_SYSTEM|CONVERSATION|USER|ASSISTANT|SYSTEM)(?=[\\s>/])", RegexOption.IGNORE_CASE)

/**
 * Isi pesan (halaman web, email, hasil tool) tidak boleh meniru pembatas prompt. Tag struktural dinetralkan dengan
 * zero-width space setelah "<" sehingga tidak ada yang bisa menutup blok lalu menyisipkan blok sistem palsu.
 */
internal fun escapeStructuralTags(text: String): String =
    STRUCTURAL_TAG.replace(text) { "<" + it.groupValues[1] + "\u200B" + it.groupValues[2] }

/** Batas gambar yang diteruskan ke Codex per giliran (sama dengan batas lampiran per pesan). */
internal const val CODEX_MAX_IMAGES = 8

/**
 * Gambar yang dikirim ke Codex: hanya dari pesan TERAKHIR yang membawa gambar (lampiran baru atau hasil ViewImages).
 * Gambar di pesan lama sudah dibuang aplikasi di giliran berikutnya, jadi tidak dikirim ulang.
 */
internal fun codexImages(messages: List<Msg>): List<ImagePart> =
    messages.lastOrNull { it.images.isNotEmpty() }?.images.orEmpty().take(CODEX_MAX_IMAGES)

internal fun codexPrompt(system: String, messages: List<Msg>, native: Boolean = false, workspace: Boolean = false): String =
    if (native) (if (workspace) codexWorkspacePrompt(system, messages) else codexNativePrompt(system, messages)) else codexModelOnlyPrompt(system, messages)

/**
 * PATCH31: mode native dengan salinan kerja. Direktori kerja Codex adalah salinan proyek Mythos di Termux, jadi berkas proyek
 * dan lampiran (uploads/, ZIP sudah diekstrak) dibaca dan diedit langsung dengan tool bawaan; hasilnya disalin balik otomatis.
 */
private fun codexWorkspacePrompt(system: String, messages: List<Msg>): String = buildString {
    appendLine("Kamu adalah Codex yang berjalan di Termux (Android) di dalam aplikasi Mythos, seperti Codex CLI.")
    appendLine("Kamu punya AKSES PENUH ke Termux di HP ini tanpa sandbox dan tanpa dialog persetujuan: jalankan perintah shell apa pun yang dibutuhkan tugas (HOME Termux, ~/storage dan /sdcard bila termux-setup-storage sudah dijalankan, pkg, find, du, rm, dll). Non-interaktif: pakai flag -y/--yes dan jangan membuka editor atau prompt. Untuk tugas pembersihan: tampilkan dulu apa yang akan dihapus (ls/du/find), baru hapus, dan laporkan hasil nyatanya.")
    appendLine("DIREKTORI KERJA = salinan folder proyek Mythos di Termux. Lampiran pengguna ada di uploads/ (ZIP sudah diekstrak ke uploads/<nama>/). Baca, cari, dan ubah berkas proyek langsung dengan shell dan tool edit berkasmu (ls, cat, grep, apply_patch, dll); path relatif terhadap direktori kerja ini. Saat sesi selesai, berkas yang kamu ubah, buat, atau hapus di sini otomatis disalin balik ke proyek Mythos. Folder .git, build, node_modules, dan .gradle tidak ikut disalin dan perubahan di dalamnya tidak dikembalikan; berkas lebih besar dari 700 KB tidak dikembalikan.")
    appendLine("Alat Mythos (Read, Glob, Grep, Write, Edit, WebSearch, WebFetch) bukan tool bawaan Codex dan tidak muncul di daftar tool-mu; itu normal dan untuk berkas proyek kamu tidak membutuhkannya. Jangan pernah menjawab bahwa lampiran atau berkas proyek tidak tersedia: lihat dulu direktori kerjamu (ls uploads/) sebelum menyimpulkan apa pun, lalu kerjakan sampai selesai dan laporkan hasil nyatanya.")
    appendLine("Balas sesuai instruksi sistem Mythos berikut. Jika instruksi meminta format <tool_use> atau protokol lain, keluarkan format itu sebagai teks persis untuk diproses Mythos. Semua perintah shell lewat tool shell Codex (akses penuh, tanpa persetujuan).")
    appendLine()
    appendConversation(system, messages)
}

/**
 * Mode Codex CLI: Codex menjalankan tool shell hanya di ruang kerja terisolasi (workspace-write). Perintah di ruang kerja dapat berjalan di dalam batas sandbox; akses jaringan atau tindakan di luar batas harus menunggu persetujuan aplikasi. Berkas proyek Mythos (penyimpanan pribadi aplikasi, tidak terjangkau Termux) dibaca dan ditulis lewat alat Mythos (blok <tool_use>).
 */
private fun codexNativePrompt(system: String, messages: List<Msg>): String = buildString {
    appendLine("Kamu adalah Codex yang berjalan di Termux (Android) di dalam aplikasi Mythos, seperti Codex CLI.")
    appendLine("Kamu punya AKSES PENUH ke Termux di HP ini tanpa sandbox dan tanpa dialog persetujuan: jalankan perintah shell apa pun yang dibutuhkan tugas (HOME Termux, ~/storage dan /sdcard bila termux-setup-storage sudah dijalankan, pkg, find, du, rm, dll). Direktori kerja awal = folder sesi sementara, tapi kamu boleh berpindah ke mana saja. Non-interaktif: pakai flag -y/--yes dan jangan membuka editor atau prompt. Untuk tugas pembersihan: tampilkan dulu apa yang akan dihapus (ls/du/find), baru hapus, dan laporkan hasil nyatanya.")
    appendLine("FOLDER PROYEK MYTHOS (termasuk lampiran di uploads/) ada di penyimpanan pribadi aplikasi Mythos dan TIDAK bisa dibuka dari Termux: ls, cat, find, dan sejenisnya ke folder itu pasti gagal dengan \"Permission denied\", jadi jangan mencobanya lewat shell. Untuk membaca atau menulis berkas proyek, dan untuk audit atau analisis kode proyek, SELALU pakai alat Mythos lewat blok <tool_use>{...}</tool_use> di jawaban teksmu: Glob/Grep untuk mencari (mis. Glob **/* untuk peta berkas), Read untuk membaca, Write/Edit untuk mengubah. Pakai tool shell bawaanmu hanya untuk urusan di Termux (pkg, git, node, python, dll).")
    appendLine("PENTING: Read, Glob, Grep, Write, Edit, WebSearch, WebFetch BUKAN fungsi/tool bawaan Codex dan memang tidak akan muncul di daftar tool-mu. Itu normal. Alat itu TERSEDIA lewat blok teks <tool_use>{\"name\": \"Read\", \"input\": {...}}</tool_use> di jawabanmu; Mythos yang menjalankannya dan mengirim hasilnya di giliran berikutnya. Path Read/Glob relatif terhadap folder proyek Mythos, bukan HOME Termux, jadi batasan HOME hanya berlaku untuk tool shell, bukan untuk Read. Jangan pernah menjawab bahwa Read/Glob atau lampiran tidak tersedia, dan jangan menyuruh pengguna membuka ulang sesi: tulis blok <tool_use> yang sesuai (mis. Glob uploads/** lalu Read).")
    appendLine("Balas sesuai instruksi sistem Mythos berikut. Jika instruksi meminta format <tool_use> atau protokol lain, keluarkan format itu sebagai teks persis untuk diproses Mythos. Semua perintah shell lewat tool shell Codex (akses penuh, tanpa persetujuan).")
    appendLine()
    appendConversation(system, messages)
}

private fun StringBuilder.appendConversation(system: String, messages: List<Msg>) {
    appendLine("<MYTHOS_SYSTEM>")
    appendLine(escapeStructuralTags(system))
    appendLine("</MYTHOS_SYSTEM>")
    appendLine("<CONVERSATION>")
    messages.forEach { message ->
        appendLine("<${message.role.name}>")
        appendLine(escapeStructuralTags(message.text))
        if (message.images.isNotEmpty()) {
            appendLine("[${message.images.size.coerceAtMost(CODEX_MAX_IMAGES)} gambar terlampir langsung pada pesan ini sebagai input gambar; lihat dan jelaskan sendiri, jangan membukanya dengan Read atau perintah shell.]")
        }
        appendLine("</${message.role.name}>")
    }
    appendLine("</CONVERSATION>")
}

private fun codexModelOnlyPrompt(system: String, messages: List<Msg>): String = buildString {
    appendLine("Kamu adalah endpoint model bahasa untuk aplikasi Mythos, bukan agen CLI mandiri.")
    appendLine("Jangan memakai tool bawaan Codex sendiri (membaca/menulis file, menjalankan perintah, membuka jaringan, meminta persetujuan tool); semua itu hanya lewat alat Mythos yang disebut di bawah.")
    appendLine("PENTING: alat Mythos (Read, Glob, Grep, Write, Edit, dan lainnya sesuai daftar di instruksi sistem) TERSEDIA untukmu, tetapi hanya lewat blok <tool_use>{...}</tool_use> di dalam jawaban teksmu; Mythos yang menjalankannya. Lampiran pengguna ada di folder uploads/. Jangan pernah menjawab bahwa alat atau lampiran tidak tersedia: panggil alat Mythos yang sesuai.")
    appendLine("PEMETAAN ALAT: mencari di web = tulis <tool_use>{\"name\": \"WebSearch\", \"input\": {\"query\": \"...\"}}</tool_use>; membuka halaman = WebFetch; membaca file = Read/Glob/Grep; menulis file = Write/Edit; menjalankan perintah shell/terminal tidak tersedia di mode ini (tidak ada alat untuk itu; katakan terus terang bila diminta). Kamu tidak punya pencarian web bawaan; jangan pernah mencoba webSearch/mcpToolCall/commandExecution/fileChange milik Codex, karena percobaan itu langsung dibatalkan. Bila suatu alat tidak ada di daftar instruksi sistem, jawab langsung tanpa alat.")
    appendLine("Balas hanya sesuai instruksi sistem Mythos berikut. Jika instruksi meminta format <tool_use> atau protokol lain, keluarkan format itu sebagai teks persis untuk diproses Mythos.")
    appendLine()
    appendConversation(system, messages)
}
