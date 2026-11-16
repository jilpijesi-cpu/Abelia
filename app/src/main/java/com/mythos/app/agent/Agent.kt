package com.mythos.app.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicReference

fun interface Approver {
    suspend fun approve(tool: String, summary: String): Boolean
}

/**
 * Mode izin ala Claude Code:
 *  DEFAULT      : Write/Edit/GithubBuild/Delete meminta persetujuan.
 *  ACCEPT_EDITS : Write/Edit otomatis; GithubBuild otomatis kecuali workflow baru/berubah; Delete tetap meminta.
 *  PLAN         : hanya alat baca; rencana diajukan lewat ExitPlanMode.
 */
enum class PermissionMode { DEFAULT, ACCEPT_EDITS, PLAN }

data class AgentConfig(
    val maxSteps: Int = 300,             // maksimum giliran model (patch7: naik dari 120)
    val maxFormatRepairs: Int = 3,
    val maxConsecutiveErrors: Int = 3,   // mulai memaksa refleksi
    val abortAfterErrors: Int = 7,       // berhenti agar tidak membakar token
    val contextBudgetChars: Int = 60_000,
    val keepRecentMessages: Int = 12,
    val maxObservationChars: Int = 16_000,
    val permissionMode: PermissionMode = PermissionMode.ACCEPT_EDITS,
    /** Sapaan untuk pengguna. Bawaan "BOS"; isi null untuk tanpa sapaan khusus seperti Claude Code. */
    val userAddress: String? = "BOS",
    /** Ingatkan memperbarui TodoWrite setelah sekian giliran alat tanpa pembaruan. */
    val todoReminderEvery: Int = 6,
    /** Balasan pertama tiap tugas wajib diawali kalimat pembuka sebelum alat berjalan; bila model lupa, aplikasi menyisipkan kalimat bawaan. */
    val ackFirst: Boolean = true,
    /** true = mode Codex native: Codex menjalankan shell-nya sendiri di Termux (satu-satunya jalur ke Termux; model lain tidak punya). */
    val ownShell: Boolean = false,
)

/**
 * Siklus agen ala Claude Code:
 *   kumpulkan konteks -> bertindak (alat, boleh beberapa sekaligus) -> lihat hasil -> ulangi,
 * dan berhenti ketika model membalas TANPA pemanggilan alat (balasan itu adalah jawaban akhir).
 * Tidak ada gerbang yang menolak jawaban; kejujuran soal verifikasi diatur lewat prompt, dan
 * flag `verified` pada Final hanya informasi (tidak ada perubahan yang belum dibaca ulang/di-build).
 */
class Agent(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val sandbox: ProjectSandbox,
    private val approver: Approver,
    private val ask: suspend (String) -> String,
    private val builder: BuildService? = null,
    private val cfg: AgentConfig = AgentConfig(),
    private val journal: TaskJournal? = null,
    private val device: DeviceActions? = null,
    private val workspace: GoogleWorkspaceService? = null,
) {
    private class RunState(var mode: PermissionMode) {
        var step = 0
        var consecutiveErrors = 0
        var stateVersion = 0
        var compacted = false
        var calls = 0
        var idSeq = 0
        var sinceTodo = 0
        var nudges = 0
        var toolClaimNudged = false              // PATCH7: koreksi 'alat tersedia' baru diberikan sekali
        var usedTools = false                    // PATCH7: sudah ada alat yang benar-benar dipanggil pada tugas ini
        var lastRejected = ""                    // PATCH7: balasan terakhir yang ditolak 'terlalu dini' (cegah putaran jawaban sama)
        var persist = 0                          // berapa kali agen dipaksa lanjut karena tujuan belum tercapai
        var taintedByExternal = false            // sesi sudah membaca konten eksternal (WebFetch/WebSearch)
        var buildFailed = false                  // build GitHub terakhir gagal: tugas belum selesai
        var extensions = 0
        var budgetChars = 0                      // anggaran konteks (karakter) yang berjalan; dikecilkan bila penyedia menolak ukuran (413)
        var shrinks = 0
        var budget = 0                           // batas langkah berjalan (bisa diperpanjang otomatis)
        var spoke = false                        // sudah ada kalimat pembuka/narasi pada tugas ini
        var intactSteps = Int.MAX_VALUE          // langkah terbaru yang hasilnya masih utuh di konteks yang dikirim
        var approvedWorkflow: String? = null     // isi workflow yang sudah disetujui pengguna untuk dijalankan di GitHub
        var lastPlan: List<PlanStep> = emptyList()
        val dirty = linkedSetOf<String>()
        val seen = HashMap<String, IntArray>()   // signature -> [stateVersion, step]
        val denied = HashSet<String>()
        val web = WebFetchGuard()                // patch13: batas alamat berbeda per situs + deteksi halaman kembar
    }

    private data class Outcome(val ok: Boolean, val text: String, val countsAsError: Boolean, val images: List<ImagePart> = emptyList())

    fun events(task: String, images: List<ImagePart> = emptyList()): Flow<AgentEvent> =
        channelFlow { execute(task, images) { send(it) } }

    suspend fun execute(task: String, emit: suspend (AgentEvent) -> Unit): AgentResult = execute(task, emptyList(), emit)

    suspend fun execute(task: String, images: List<ImagePart>, emit: suspend (AgentEvent) -> Unit): AgentResult {
        val st = RunState(cfg.permissionMode)
        st.web.trustFromText(userOnlyTextForTrust(task)) // URL yang diketik pengguna sendiri sah dibuka (bukan balasan Mythos/catatan upload)
        val session = SessionState()
        return try {
            loop(task, images, st, session, emit)
        } catch (e: AgentAbort) {
            val msg = e.message ?: "Dihentikan"
            emit(AgentEvent.Failed(msg))
            AgentResult(false, msg, false, st.step, st.taintedByExternal)
        }
    }

    private val auditLog = SecurityAuditLog(sandbox.root)

    private fun loadMemory(task: String = ""): String {
        val parts = ArrayList<String>()
        for (name in listOf("CLAUDE.md", "MYTHOS.md")) {
            val f = File(sandbox.root, name)
            if (f.isFile && f.length() < 200_000) { parts.add("Contents of $name (project instructions):\n" + clip(f.readText(), 6_000)); break }
        }
        val kasus = File(sandbox.root, "KASUS.md")
        if (kasus.isFile && kasus.length() < 200_000) {
            parts.add("Contents of KASUS.md (case file written by you earlier: tickets, dates, claims, evidence, deadlines; trust it, and update it whenever a new fact appears):\n" + clip(kasus.readText(), 8_000))
        }
        Playbooks.render(sandbox.root)?.let { parts.add(it) }
        if (task.isNotBlank()) {
            Playbooks.renderBundledForTask(sandbox.root, task)?.let { parts.add(it) }
        }
        return parts.joinToString("\n\n")
    }

    private suspend fun loop(task: String, images: List<ImagePart>, st: RunState, session: SessionState, emit: suspend (AgentEvent) -> Unit): AgentResult {
        val env = ToolEnv(sandbox, session, ask, builder, journal, device, workspace)
        // Sapaan di proyek kosong cukup dengan prompt ringkas; selain itu prompt penuh (bagian pengaduan hanya bila relevan).
        val light = LightChat.eligible(sandbox.root, task, images.isNotEmpty())
        val needCases = File(sandbox.root, "KASUS.md").isFile || CASE_WORDS.containsMatchIn(task)
        val system = if (light) Protocol.lightSystemPrompt(cfg.userAddress) else Protocol.systemPrompt(
            tools, builder != null, builder?.hint.orEmpty(), cfg.userAddress, planMode = cfg.permissionMode == PermissionMode.PLAN,
            includeCases = needCases
        )
        val history = mutableListOf<Msg>()
        history.add(
            if (light) Msg(Role.USER, task)
            else Msg(
                Role.USER,
                Protocol.firstMessage(task, clip(renderTree(sandbox.root, 5), 6_000), loadMemory(task), sandbox.root.path, shell = cfg.ownShell, shellVia = "Codex"),
                images
            )
        )

        st.budget = cfg.maxSteps
        st.budgetChars = cfg.contextBudgetChars
        while (true) {
            currentCoroutineContext().ensureActive()
            if (st.step >= st.budget && st.buildFailed && st.extensions < MAX_EXTENSIONS) {
                // Build belum hijau: jangan menyerah karena batas langkah, perpanjang otomatis.
                st.extensions++
                st.budget += EXTENSION_STEPS
                emit(AgentEvent.Notice("Build belum berhasil, batas langkah diperpanjang otomatis (+$EXTENSION_STEPS) agar agen terus memperbaiki."))
            }
            if (st.step >= st.budget) {
                throw AgentAbort("Batas ${st.budget} langkah tercapai sebelum tugas selesai. ${progress(st, session)}")
            }

            val reply = decide(system, history, st, emit)
            st.step++

            // Model lemah sering menulis niat ("Saya akan mencari ...") lalu berhenti tanpa memanggil alat.
            // Itu bukan jawaban: tampilkan sebagai narasi dan minta ia benar-benar bertindak (maks. 2 kali per tugas).
            if (reply.calls.isEmpty() && st.nudges < MAX_NUDGES && looksLikeUnfulfilledIntent(reply.text, noToolsYet = !st.usedTools)) {
                st.nudges++
                st.spoke = true
                emit(AgentEvent.Thought(st.step, reply.text))
                history.add(Msg(Role.ASSISTANT, reply.text))
                history.add(
                    Msg(
                        Role.USER,
                        Protocol.reminder(
                            "You described an action but made no tool call, so your turn would end here. Work out what the user most plausibly wants (their message may be short or vague; use the earlier conversation), pick the most sensible reading and do it: make the tool call now in a " +
                                "${Protocol.OPEN} block, without announcing it again. If the task is complete, reply with the actual result instead of a plan."
                        )
                    )
                )
                continue
            }

            // PATCH7: model kadang mengaku "alat tidak tersedia / tidak bisa membuka lampiran" padahal alatnya ada. Beri SATU koreksi
            // yang menyebut alat dan lokasi lampiran, bukan membiarkan jawaban keliru itu jadi hasil akhir.
            if (reply.calls.isEmpty() && !st.toolClaimNudged && claimsToolsUnavailable(reply.text)) {   // PATCH30: tanpa syarat !usedTools (klaim muncul justru setelah Glob/Read jalan)
                st.toolClaimNudged = true
                emit(AgentEvent.Streaming(""))
                history.add(Msg(Role.ASSISTANT, reply.text))
                // PATCH28: koreksi teks saja tidak cukup untuk model yang mengira Read/Glob harus berupa fungsi bawaan (kasus Codex).
                // Aplikasi menjalankan Glob uploads/** sendiri dan mengembalikan hasilnya dalam format protokol, sehingga model melihat
                // bahwa alurnya bekerja dan tinggal menulis blok <tool_use> Read untuk berkas yang dibutuhkan.
                if (!st.usedTools && tools.get("Glob") != null) {   // probe Glob hanya bila belum ada alat yang jalan
                    val probe = ToolCall("toolu_${++st.idSeq}", "Glob", JSONObject().put("pattern", "uploads/**"), "Memeriksa lampiran")
                    st.usedTools = true
                    val out = runBatch(listOf(probe), st, env, emit)[0]
                    history.add(
                        Msg(
                            Role.USER,
                            Protocol.result(probe, out.ok, clip(out.text, cfg.maxObservationChars)) + "\n" + Protocol.reminder(
                                "The tools listed in the system prompt (Read, Glob, Grep, Write, Edit, ...) ARE available. They are NOT native functions: you call them by writing a " +
                                    "${Protocol.OPEN} block in your reply text, and the app runs it. Proof: the app just ran Glob {\"pattern\": \"uploads/**\"} for you (result above). " +
                                    "Attached files are in uploads/ and zips are already unpacked into uploads/<name>/. Continue the task now; to read a file write " +
                                    "${Protocol.OPEN}{\"name\": \"Read\", \"input\": {\"file_path\": \"uploads/<name>/<file>\"}}</tool_use>. Do not say tools are unavailable and do not ask the user to reopen the session."
                            ),
                            out.images
                        )
                    )
                    continue
                }
                history.add(
                    Msg(
                        Role.USER,
                        Protocol.reminder(
                            "The tools listed in the system prompt (Read, Glob, Grep, Write, Edit, ...) ARE available in this session" +
                                (if (st.usedTools) " (the app already ran some of your ${Protocol.OPEN} calls in this task, so the blocks DO work). " else ". ") +
                                "Attached files are in uploads/ and zips are already unpacked into uploads/<name>/. " +
                                "Do not say tools are unavailable and do not end the turn with a plan: make the tool call now (for example Glob uploads/** or Read/Edit on the file you need) and continue the task."
                        )
                    )
                )
                continue
            }

            // Tujuan belum tercapai (build terakhir gagal, atau agen sendiri bilang belum berhasil): jangan berhenti dan
            // jangan menyuruh pengguna mengulang perintah. Paksa lanjut memperbaiki, dengan batas supaya tidak membakar token.
            if (reply.calls.isEmpty() && shouldKeepGoing(st, reply.text) && !sameReply(st.lastRejected, reply.text)) {
                st.persist++
                st.lastRejected = reply.text
                emit(AgentEvent.Streaming(""))   // balasan yang ditolak tidak boleh tersisa di layar (itu yang tampak seperti bug sekilas)
                if (st.persist == 1) emit(AgentEvent.Notice("Belum berhasil. Agen melanjutkan memperbaiki sendiri, Anda tidak perlu mengulang perintah."))
                history.add(Msg(Role.ASSISTANT, reply.text))
                history.add(Msg(Role.USER, Protocol.reminder(KEEP_GOING_HINT)))
                continue
            }

            // Tanpa pemanggilan alat = giliran selesai; teksnya adalah jawaban.
            if (reply.calls.isEmpty()) {
                val verified = st.dirty.isEmpty()
                // Lapis kedua aturan "ONE LANGUAGE": huruf CJK nyasar dibuang kecuali pengguna sendiri memakainya.
                val finalText = ScriptGuard.strip(reply.text, history.filter { it.role == Role.USER }.joinToString("\n") { it.text })
                emit(AgentEvent.Final(finalText, verified, st.step))
                return AgentResult(true, finalText, verified, st.step, st.taintedByExternal)
            }

            st.usedTools = true
            // Bila balasan model tidak memiliki teks narasi (mis. pemanggilan teks polos), bersihkan sisa pratinjau streaming lebih dulu.
            if (reply.text.isBlank()) {
                emit(AgentEvent.Streaming(""))
            }
            // Jawab dulu, baru kerja: balasan pertama tanpa kalimat pembuka diberi kalimat bawaan agar percakapan terasa mengalir
            // (kecuali bila model langsung bertanya ke pengguna lewat AskUserQuestion).
            val asksUserFirst = reply.calls.all { it.name.equals("AskUserQuestion", ignoreCase = true) }
            val narration = if (reply.text.isBlank() && cfg.ackFirst && !st.spoke && !asksUserFirst) openingLine(cfg.userAddress) else reply.text
            if (narration.isNotBlank()) {
                st.spoke = true
                emit(AgentEvent.Thought(st.step, narration))
            }
            history.add(Msg(Role.ASSISTANT, Protocol.render(narration, reply.calls)))

            val results = ArrayList<String>()
            var failed = 0
            var succeeded = 0
            var usedTodo = false
            val outcomes = runBatch(reply.calls, st, env, emit)
            for ((i, call) in reply.calls.withIndex()) {
                val o = outcomes[i]
                results.add(Protocol.result(call, o.ok, clip(o.text, cfg.maxObservationChars)))
                if (o.ok) succeeded++ else if (o.countsAsError) failed++
                if (call.name.equals("TodoWrite", ignoreCase = true)) usedTodo = true
            }

            if (succeeded > 0) st.consecutiveErrors = 0 else if (failed > 0) st.consecutiveErrors++
            if (st.consecutiveErrors >= cfg.abortAfterErrors) {
                throw AgentAbort("Terjebak: ${st.consecutiveErrors} kegagalan berturut-turut. ${progress(st, session)}")
            }
            if (session.plan != st.lastPlan) {
                st.lastPlan = session.plan
                emit(AgentEvent.PlanChanged(session.plan))
            }

            val notes = mutableListOf<String>()
            if (st.consecutiveErrors == 2) {
                notes.add("2 consecutive failures. Re-check your assumptions with Read/Grep before the next attempt; do not repeat the same call.")
            } else if (st.consecutiveErrors >= cfg.maxConsecutiveErrors) {
                notes.add(
                    "${st.consecutiveErrors} consecutive failures. Before acting again, state (1) exactly what failed, (2) 2-3 hypotheses for the root cause, " +
                        "(3) the cheapest tool call that discriminates between them. Do not repeat any earlier approach. If you are blocked on a decision, use AskUserQuestion."
                )
            }
            st.sinceTodo = if (usedTodo) 0 else st.sinceTodo + 1
            val open = session.plan.any { it.status == StepStatus.TODO || it.status == StepStatus.DOING }
            if (open && st.sinceTodo >= cfg.todoReminderEvery) {
                st.sinceTodo = 0
                notes.add("The TodoWrite tool has not been used recently. If the task still benefits from tracking, update the todo list. Current todos:\n" + renderPlan(session.plan))
            }
            val left = st.budget - st.step
            if (left <= 3) notes.add("Only $left turns left. Verify what you have and finish, or report precisely what remains.")

            val body = results.joinToString("\n") + if (notes.isEmpty()) "" else "\n" + Protocol.reminder(notes.joinToString("\n"))
            history.add(Msg(Role.USER, body, outcomes.flatMap { it.images }))
        }
    }

    /**
     * Meminta balasan model dengan streaming dan meneruskan teks yang sedang tiba sebagai [AgentEvent.Streaming].
     * Pendengar jaringan menulis ke Channel CONFLATED (hanya teks terbaru yang penting), jadi jaringan tidak pernah menunggu UI.
     *
     * [holdPlain]: selama build masih gagal, teks polos tanpa alat TIDAK ditampilkan selagi mengalir. Balasan semacam itu ("Build gagal, saya berhenti...")
     * akan ditolak dan diulang, jadi menampilkannya hanya membuat layar berkedip. Narasi yang disusul panggilan alat tetap tampil normal.
     */
    /** Skema function calling asli untuk semua alat sesi ini (dibuat sekali). Klien yang tidak mendukungnya mengabaikannya. */
    private val nativeSpecs: List<NativeToolSpec> by lazy { NativeTools.specsFor(tools.tools) }

    private suspend fun streamReply(system: String, view: List<Msg>, emit: suspend (AgentEvent) -> Unit, holdPlain: Boolean = false): StreamOut = coroutineScope {
        LatencyTrace.modelCall()
        val live = Channel<String>(Channel.CONFLATED)
        launch(start = CoroutineStart.UNDISPATCHED) {
            for (partial in live) {
                val tool = Protocol.pendingTool(partial)
                if (holdPlain && tool == null) continue
                emit(AgentEvent.Streaming(Protocol.visibleText(partial), tool?.first, tool?.second, Protocol.pendingStatus(partial)))
            }
        }
        // Model lemah kadang macet menulis kalimat niat yang sama berulang-ulang tanpa pernah memanggil alat, sampai batas keluarannya habis.
        // Begitu pola itu terdeteksi, aliran diputus lebih awal (menghemat token dan waktu) dan balasannya diperlakukan sebagai rusak.
        val latest = AtomicReference("")
        val tripped = CompletableDeferred<Unit>()
        // Model (terutama model gratis di balik openrouter/free) kadang melanjutkan menulis <tool_result> karangannya sendiri.
        // Aliran diputus begitu penanda itu tampil; teks sebelum penanda tetap dipakai (mungkin sudah memuat panggilan yang sah).
        val faked = CompletableDeferred<Unit>()
        val worker = async(NativeToolsContext(nativeSpecs)) {
            llm.stream(system, view) { partial ->
                latest.set(partial)
                live.trySend(partial)
                if (!tripped.isCompleted && isRepetitionLoop(partial)) tripped.complete(Unit)
                if (!faked.isCompleted && Protocol.fakeResultCut(partial) >= 0) faked.complete(Unit)
            }
        }
        val watcher = launch { tripped.await(); worker.cancel() }
        val fakeWatcher = launch { faked.await(); worker.cancel() }
        try {
            val text = try {
                worker.await()
            } catch (e: CancellationException) {
                if (!tripped.isCompleted && !faked.isCompleted) throw e
                latest.get()
            }
            val cut = Protocol.fakeResultCut(text)
            StreamOut(if (cut >= 0) text.substring(0, cut) else text, tripped.isCompleted)
        } finally {
            watcher.cancel()
            fakeWatcher.cancel()
            live.close()
        }
    }

    private class StreamOut(val text: String, val looped: Boolean)

    /** Minta balasan berikutnya dari model, dengan perbaikan format otomatis bila blok alat rusak. */
    private suspend fun decide(system: String, history: MutableList<Msg>, st: RunState, emit: suspend (AgentEvent) -> Unit): Parsed.Reply {
        var repairs = 0
        var shrinkTries = 0   // percobaan memperkecil konteks UNTUK panggilan ini saja; st.shrinks hanya penanda "pemangkasan paksa aktif"
        while (true) {
            val packed = ContextManager.compactWithInfo(history, st.budgetChars, cfg.keepRecentMessages)
            val view = if (st.shrinks > 0) ContextManager.squeeze(packed.view, st.budgetChars) else packed.view
            st.intactSteps = if (packed.compacted || st.shrinks > 0) packed.keep / 2 else Int.MAX_VALUE
            if (packed.compacted && !st.compacted) {
                st.compacted = true
                emit(AgentEvent.Notice("Konteks dipadatkan: hasil alat lama diringkas."))
            }
            st.calls++
            // Gambar awal mahal bila dikirim ulang terus: setelah MAX_IMAGE_CALLS hanya pesan terbaru (mis. hasil ViewImages) yang membawa gambar.
            val sendView = if (st.calls > MAX_IMAGE_CALLS && view.any { it.images.isNotEmpty() }) view.mapIndexed { idx, it ->
                if (it.images.isEmpty() || (idx == view.lastIndex && idx > 0)) it
                else Msg(it.role, it.text + "\n[lampiran gambar sudah ditampilkan pada langkah awal; rujuk catatan Anda sendiri]")
            } else view
            val out = try {
                streamReply(system, sendView, emit, holdPlain = st.buildFailed)
            } catch (e: LlmException) {
                if (e.code == 413 && ContextManager.fixedPartTooLarge(system.length, e.message)) {
                    throw AgentAbort("Gagal memanggil model: " + ContextManager.describeFixedTooLarge(system.length, e.message))
                }
                if (e.code == 413 && shrinkTries < MAX_SHRINKS) {
                    // Penyedia menolak ukuran permintaan: perkecil konteks (hasil alat lama, isi lampiran besar) lalu coba lagi, tanpa kartu baru di layar.
                    shrinkTries++
                    st.shrinks++
                    val sentChars = sendView.sumOf { it.text.length }
                    st.budgetChars = ContextManager.shrunkBudget(minOf(st.budgetChars, sentChars), e.message)
                    emit(AgentEvent.Streaming(""))
                    continue
                }
                // Codex kadang menyelesaikan giliran tanpa teks sama sekali (mis. hanya berpikir). Itu bukan kegagalan jaringan: perlakukan
                // sebagai balasan kosong dan minta ia langsung bertindak, bukan menghentikan seluruh tugas.
                if (e.message.orEmpty().contains("tidak mengeluarkan teks jawaban")) {
                    repairs++
                    if (repairs > cfg.maxFormatRepairs) {
                        throw AgentAbort("Codex berulang kali tidak mengeluarkan jawaban. Coba kirim ulang, atau pilih penyedia lain di Pengaturan.")
                    }
                    emit(AgentEvent.Streaming(""))
                    emit(AgentEvent.Notice("Codex membalas kosong, meminta ia langsung bertindak ($repairs/${cfg.maxFormatRepairs})."))
                    history.add(Msg(Role.ASSISTANT, "(kosong)"))
                    history.add(
                        Msg(
                            Role.USER,
                            Protocol.reminder(
                                "Your last reply was empty. Continue the task now: work out what the user wants from their message and the attached files " +
                                    "(attachments are in uploads/), then make the tool call as ${Protocol.OPEN}{\"name\": \"<Tool>\", \"input\": {...}}${Protocol.CLOSE} " +
                                    "(for example Read on the attachment), or give the final answer in plain text. Do not reply with nothing."
                            )
                        )
                    )
                    continue
                }
                val hint = if (e.code == 413) " Konteks sudah diperkecil ${shrinkTries}x tetapi tetap melebihi batas penyedia (bagian yang dipotong tidak dibaca model). Pakai model/penyedia dengan batas token lebih besar, atau kirim lampiran lebih kecil." else ""
                val shown = if (e.code == 413) ContextManager.describeTooLarge(e.message) else e.message
                throw AgentAbort("Gagal memanggil model: $shown$hint")
            }
            val raw = out.text
            if (out.looped) {
                repairs++
                if (repairs > cfg.maxFormatRepairs) {
                    throw AgentAbort("Model terjebak menulis kalimat yang sama berulang-ulang tanpa memanggil alat. Coba pilih model lain di Pengaturan, atau matikan mode Riset.")
                }
                emit(AgentEvent.Streaming(""))
                emit(AgentEvent.Notice("Model mengulang kalimat yang sama; aliran dihentikan dan model diminta langsung memanggil alat ($repairs/${cfg.maxFormatRepairs})."))
                history.add(Msg(Role.ASSISTANT, "(balasan dibuang: mengulang kalimat yang sama)"))
                history.add(
                    Msg(
                        Role.USER,
                        Protocol.reminder(
                            "Your last reply got stuck repeating the same sentence and was discarded. Do NOT describe or announce what you will do. " +
                                "Reply now with ONLY the tool call(s), each as ${Protocol.OPEN}{\"name\": \"<Tool>\", \"input\": {...}}${Protocol.CLOSE}, " +
                                "or, if you already have enough information, with the final answer in plain text."
                        )
                    )
                )
                continue
            }
            val parsed = Protocol.parse(raw) { "toolu_${++st.idSeq}" }
            val problem: String? = when (parsed) {
                is Parsed.Reply -> if (parsed.calls.isEmpty() && parsed.text.isBlank()) "balasan kosong." else null
                is Parsed.Invalid -> parsed.reason
            }
            if (problem == null) return parsed as Parsed.Reply
            repairs++
            val truncated = parsed is Parsed.Invalid && parsed.truncated
            if (repairs > cfg.maxFormatRepairs) {
                throw AgentAbort(
                    if (truncated) "Balasan model terus terpotong oleh batas keluarannya (file terlalu besar untuk satu panggilan). Minta tugas dipecah menjadi file yang lebih kecil. (balasan terakhir ${raw.length} karakter)"
                    else "Model berulang kali membalas dengan format tidak valid: $problem"
                )
            }
            emit(AgentEvent.Streaming(""))   // buang pratinjau percobaan yang gagal
            emit(
                AgentEvent.Notice(
                    if (truncated) "Format balasan terpotong oleh batas keluaran model, meminta dipecah ($repairs/${cfg.maxFormatRepairs}; ${raw.length} karakter)."
                    else "Format balasan tidak valid, meminta perbaikan ($repairs/${cfg.maxFormatRepairs}): $problem"
                )
            )
            history.add(Msg(Role.ASSISTANT, clip(raw.ifBlank { "(kosong)" }, 1_500)))
            history.add(
                Msg(
                    Role.USER,
                    Protocol.reminder(
                        if (truncated) TRUNCATED_HINT
                        else "Your last reply could not be used: $problem\nResend it. Tool calls must be " +
                            "${Protocol.OPEN}{\"name\": \"<Tool>\", \"input\": {...}}${Protocol.CLOSE} with valid JSON; to finish, reply with plain text and no tool_use block."
                    )
                )
            )
        }
    }

    /**
     * Menjalankan semua panggilan alat dalam satu balasan, hasilnya SELALU berurutan sesuai panggilan.
     * Hanya deretan panggilan "Task" yang berurutan (>= 2) dijalankan bersamaan; alat lain (tulis, build, tanya pengguna, dst.)
     * tetap satu per satu seperti biasa, karena mengubah state dan sebagian memerlukan persetujuan.
     */
    private suspend fun runBatch(calls: List<ToolCall>, st: RunState, env: ToolEnv, emit: suspend (AgentEvent) -> Unit): List<Outcome> {
        val out = ArrayList<Outcome>(calls.size)
        var i = 0
        while (i < calls.size) {
            currentCoroutineContext().ensureActive()
            var j = i
            while (j < calls.size && isParallelTask(calls[j])) j++
            if (j - i >= 2) {
                out.addAll(runTasksParallel(calls.subList(i, j), st, env, emit))
                i = j
            } else {
                out.add(runTool(calls[i], st, env, emit))
                i++
            }
        }
        return out
    }

    private fun isParallelTask(call: ToolCall): Boolean = call.name.equals("Task", ignoreCase = true) && tools.get("Task") != null

    /** Sub-agen read-only (Task) berjalan bersamaan; maksimal [MAX_PARALLEL_TASKS] sekaligus dan [MAX_TASKS_PER_TURN] per giliran. */
    private suspend fun runTasksParallel(calls: List<ToolCall>, st: RunState, env: ToolEnv, emit: suspend (AgentEvent) -> Unit): List<Outcome> {
        val tool = tools.get("Task")!!
        val accepted = calls.take(MAX_TASKS_PER_TURN)
        val summaries = accepted.map { tool.describe(it.input) }
        accepted.forEachIndexed { k, c -> emit(AgentEvent.ToolStart(st.step, tool.name, summaries[k], c.status)) }
        val gate = Semaphore(MAX_PARALLEL_TASKS)
        val done = coroutineScope {
            accepted.mapIndexed { k, c ->
                async {
                    gate.withPermit {
                        val t0 = System.nanoTime()
                        val r = execTool(tool, c.input, env)
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        emit(AgentEvent.ToolEnd(st.step, tool.name, r.ok, oneLine(r.text, 200), ms, summaries[k]))
                        auditLog.record(st.step, tool.name, tool.risk, st.taintedByExternal, false, r.ok, summaries[k])
                        r
                    }
                }
            }.awaitAll()
        }
        // Sub-agen web atau sub-agen yang membaca uploads/ membawa konten eksternal: tandai sesi tercemar sebelum alat berikutnya berjalan.
        if ((tool.taintsContext && done.any { it.ok }) || done.any { it.tainted }) st.taintedByExternal = true
        val res = done.map { Outcome(it.ok, it.text, !it.ok) }.toMutableList()
        for (extra in calls.drop(MAX_TASKS_PER_TURN)) {
            res.add(Outcome(false, "Too many Task calls in one reply (max $MAX_TASKS_PER_TURN). Wait for these reports, then call the rest in your next reply.", false))
        }
        return res
    }

    private suspend fun runTool(call: ToolCall, st: RunState, env: ToolEnv, emit: suspend (AgentEvent) -> Unit): Outcome {
        val tool = tools.get(call.name)
            ?: return Outcome(false, "Error: No such tool available: ${call.name}. Available tools: ${tools.names.joinToString()}.", true)
        val sig = signature(tool.name, call.input)

        if (st.mode == PermissionMode.PLAN && tool.risk != Risk.SAFE) {
            return Outcome(false, "Plan mode is active: ${tool.name} is not allowed. Use read-only tools, then present your plan with ExitPlanMode.", false)
        }

        if (tool.name == "ExitPlanMode") return exitPlanMode(call, tool, st, emit)

        if (sig in st.denied) {
            return Outcome(false, "The user already denied this exact action. Do not repeat it; propose an alternative or ask with AskUserQuestion.", false)
        }

        // Aksi identik di dunia yang tidak berubah = hasil identik; jangan buang giliran.
        // Jendela duplikat tidak boleh lebih lebar dari langkah yang hasilnya masih utuh di konteks: kalau hasil lama sudah
        // "[content cleared]", model harus boleh menjalankan ulang alatnya.
        val dupWindow = minOf(cfg.keepRecentMessages / 2, st.intactSteps)
        val prev = st.seen[sig]
        if (tool.effect != Effect.META && prev != null && prev[0] == st.stateVersion && prev[1] >= st.step - dupWindow) {
            emit(AgentEvent.Notice("Aksi duplikat diblokir: ${tool.name}"))
            return Outcome(
                false,
                "Duplicate call: the identical call already ran at turn ${prev[1]} and nothing has changed since, so the result would be the same. " +
                    "Use the information you already have, or change your approach/arguments.",
                true
            )
        }

        if (tool.name == "WebFetch") {
            st.web.before(call.input.str("url").orEmpty())?.let {
                emit(AgentEvent.Notice("WebFetch dihentikan: terlalu banyak alamat berbeda di satu situs"))
                return Outcome(false, it, false)
            }
        }

        val summary = tool.describe(call.input)
        // Workflow yang baru/berubah dijalankan GitHub dengan secrets repo: selalu minta persetujuan, apa pun modenya.
        val digest = if (tool.name == "GithubBuild") workflowDigest(sandbox.root, strict = st.taintedByExternal) else ""
        val workflowChanged = digest.isNotEmpty() && digest != st.approvedWorkflow
        // WebFetch berstatus Risk.SAFE (halaman dibuka tanpa meminta izin, baik agen utama maupun sub-agen; pengaman alamat lokal/privat tetap aktif di Web.kt).
        // Setelah konten email/web eksternal masuk konteks, aksi tulis ke Kalender/Drive, eksekusi GithubBuild, dan penulisan file sensitif
        // (.github/** atau skrip build) wajib disetujui pengguna walau mode izin longgar.
        val rawTarget = (call.input.str("file_path") ?: call.input.str("path")).orEmpty().trim().replace('\\', '/')
        val targetPath = if (rawTarget.isNotEmpty()) {
            runCatching { env.sandbox.relative(env.sandbox.resolve(rawTarget)) }.getOrDefault(normalizeRelPath(rawTarget))
        } else ""
        val writesBuildOrWorkflowWhenTainted = st.taintedByExternal &&
            tool.name in setOf("Write", "Edit") &&
            (isBuildSensitivePath(targetPath) || isBuildSensitivePath(rawTarget))
        val wsAction = call.input.str("action")?.trim()?.lowercase()
        val externalExecWhenTainted = st.taintedByExternal && (
            tool.name == "GithubBuild" ||
                writesBuildOrWorkflowWhenTainted ||
                (tool.name in setOf("GoogleCalendarSync", "GoogleDriveCaseVault") &&
                    wsAction !in setOf("list", "list_upcoming", "list_files"))
            )
        // Eksfiltrasi lewat WebFetch: setelah konten eksternal masuk konteks, URL ber-query/path panjang atau situs asing perlu persetujuan.
        val exfilReason = if (tool.name == "WebFetch" && st.taintedByExternal) st.web.exfilRisk(call.input.str("url").orEmpty()) else null
        // PATCH35 (permintaan pemilik): mode Otomatis (ACCEPT_EDITS) tidak menampilkan dialog apa pun KECUALI saat MENGIRIM email
        // (GmailDraftSend action "send"). Mode "Tanya dulu" (DEFAULT) dan Rencana (PLAN) mempertahankan seluruh gerbang persetujuan lama.
        val isEmailSend = tool.name == "GmailDraftSend" && call.input.str("action")?.trim()?.lowercase() == "send"
        val needsApproval = if (st.mode == PermissionMode.ACCEPT_EDITS) {
            isEmailSend
        } else {
            tool.risk == Risk.DANGEROUS || exfilReason != null ||
                (tool.risk == Risk.WRITE && st.mode == PermissionMode.DEFAULT) ||
                workflowChanged ||
                externalExecWhenTainted
        }
        // Persetujuan harus atas isi LENGKAP workflow: langkah berbahaya tidak boleh bersembunyi di balik pemotongan tampilan.
        if (needsApproval && workflowChanged && digest.length > MAX_WORKFLOW_REVIEW_CHARS) {
            return Outcome(false, "Workflow terlalu panjang untuk ditinjau pengguna (${digest.length} karakter, batas $MAX_WORKFLOW_REVIEW_CHARS). Ringkas workflow-nya lalu panggil GithubBuild lagi.", false)
        }
        val shown = when {
            workflowChanged -> summary + "\n\nWorkflow yang akan dijalankan di GitHub (bisa memakai secrets repo):\n" + digest +
                if (externalExecWhenTainted) "\n\nKonteks memuat konten eksternal; tindakan ini perlu ditinjau sebelum dijalankan." else ""
            externalExecWhenTainted -> summary + "\n\nKonteks memuat konten eksternal; tindakan ini perlu ditinjau sebelum dijalankan."
            exfilReason != null -> summary + "\n\nKonteks memuat konten eksternal dan $exfilReason. Izinkan hanya bila Anda mengenali alamat ini: data bisa dikirim keluar lewat URL."
            else -> summary
        }
        if (needsApproval && !approver.approve(tool.name, shown)) {
            st.denied.add(sig)
            auditLog.record(st.step, tool.name, tool.risk, st.taintedByExternal, needsApproval, false, "DENIED: $summary")
            emit(AgentEvent.Notice("Pengguna menolak: $summary"))
            return Outcome(false, "The user DENIED this action. Do not retry it. Propose a safer alternative or ask with AskUserQuestion.", false)
        }
        if (workflowChanged) st.approvedWorkflow = digest

        emit(AgentEvent.ToolStart(st.step, tool.name, summary, call.status))
        val t0 = System.nanoTime()
        val result = execTool(tool, call.input, env)
        val ms = (System.nanoTime() - t0) / 1_000_000
        emit(AgentEvent.ToolEnd(st.step, tool.name, result.ok, oneLine(result.text, 200), ms, summary))
        auditLog.record(st.step, tool.name, tool.risk, st.taintedByExternal, needsApproval, result.ok, summary)
        if (result.ok && (tool.name in TAINTING_TOOLS || tool.taintsContext || result.tainted)) st.taintedByExternal = true
        // Lampiran/zip dari pihak luar di uploads/ juga konten tak tepercaya (baik melalui Read, Grep, maupun ViewImages).
        if (result.ok && tool.name in setOf("Read", "Grep", "ViewImages") &&
            (isUntrustedUploadPath(targetPath) || isUntrustedUploadPath(rawTarget) || result.readPaths.any(::isUntrustedUploadPath))
        ) st.taintedByExternal = true
        if (tool.name == "WebSearch" && result.ok) st.web.trustFromText(result.text)   // situs di hasil pencarian boleh dibuka setelah tercemar
        if (tool.name == "ComposeEmail" && result.ok) emit(emailDraftEvent(call.input))

        // Panggilan Write/Edit yang ditolak karena file belum dibaca tidak dicatat sebagai duplikat, agar setelah Read panggilan itu bisa dijalankan.
        if (result.ok || tool.effect != Effect.WRITE) {
            st.seen[sig] = intArrayOf(st.stateVersion, st.step)
        }
        if (tool.name.equals("GithubBuild", ignoreCase = true)) {
            // Build yang tak bisa jalan karena konfigurasi (token/repo belum diisi) bukan kegagalan kode yang bisa diperbaiki agen.
            st.buildFailed = !result.ok && !result.text.contains("not available", ignoreCase = true)
            if (result.ok) st.persist = 0
        }
        if (tool.effect == Effect.EXEC || (tool.effect == Effect.WRITE && result.ok)) st.stateVersion++
        if (result.ok) {
            st.dirty.addAll(result.changed)
            st.dirty.removeAll(result.readPaths.toSet())
            if (result.verifies) st.dirty.clear()
        }
        val webNote = if (tool.name == "WebFetch") st.web.after(call.input.str("url").orEmpty(), result.ok, result.text) else ""
        return Outcome(result.ok, result.text + webNote, !result.ok, result.images)
    }

    private suspend fun exitPlanMode(call: ToolCall, tool: Tool, st: RunState, emit: suspend (AgentEvent) -> Unit): Outcome {
        if (st.mode != PermissionMode.PLAN) return Outcome(false, "Plan mode is not active; just continue with the task.", false)
        val plan = call.input.str("plan") ?: return Outcome(false, "Invalid input: parameter 'plan' is required.\nUsage: ${tool.doc}", true)
        emit(AgentEvent.ToolStart(st.step, tool.name, "ExitPlanMode (rencana diajukan)"))
        val ok = approver.approve(tool.name, plan)
        emit(AgentEvent.ToolEnd(st.step, tool.name, ok, if (ok) "rencana disetujui" else "rencana ditolak", 0))
        return if (ok) {
            st.mode = PermissionMode.ACCEPT_EDITS
            Outcome(true, "User has approved your plan. Plan mode is now off and you may modify files. Start with TodoWrite to track the steps, then implement.", false)
        } else {
            Outcome(false, "The user did not approve the plan. Revise it according to their feedback, or ask what to change with AskUserQuestion.", false)
        }
    }

    private suspend fun execTool(tool: Tool, args: JSONObject, env: ToolEnv): ToolResult = try {
        withTimeout(tool.timeoutMs) { tool.run(args, env) }
    } catch (e: TimeoutCancellationException) {
        ToolResult(false, "Timed out after ${tool.timeoutMs / 1000}s.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: ArgError) {
        ToolResult(false, "InputValidationError: ${e.message}\nUsage: ${tool.doc}")
    } catch (e: SecurityException) {
        ToolResult(false, "Permission denied: ${e.message}")
    } catch (e: Exception) {
        ToolResult(false, "Error (${e.javaClass.simpleName}): ${e.message}")
    } catch (e: StackOverflowError) {
        ToolResult(false, "Error: the pattern or input is too complex (stack overflow). Simplify the regex or narrow the search.")
    } catch (e: OutOfMemoryError) {
        ToolResult(false, "Error: out of memory while running ${tool.name}. Narrow the request (smaller file, tighter path or glob).")
    }

    private fun signature(tool: String, args: JSONObject): String {
        val keys = args.keys().asSequence().toList().sorted()
        return tool + "#" + keys.joinToString("|") { k -> k + "=" + args.opt(k).toString() }.hashCode()
    }

    private fun shouldKeepGoing(st: RunState, text: String) = shouldKeepGoingFor(st.buildFailed, st.persist, text, st.usedTools)

    private fun progress(st: RunState, session: SessionState): String {
        val parts = mutableListOf<String>()
        if (st.dirty.isNotEmpty()) parts.add("Perubahan belum terverifikasi: ${st.dirty.joinToString()}.")
        if (session.plan.isNotEmpty()) parts.add("Rencana:\n" + renderPlan(session.plan))
        return parts.joinToString("\n")
    }
}

private const val MAX_IMAGE_CALLS = 12
private const val MAX_SHRINKS = 3
private val CASE_WORDS = Regex(
    "komplain|pengaduan|mengadu|sengketa|refund|pengembalian dana|ojk|bank|asuransi|marketplace|tiket|surat resmi|dispute|complain|" +
        "flip|dana|saldo|uang|ditahan|tertahan|diblokir|dibekukan|gagal transfer|transfer gagal|penipuan|ditipu|cs |customer service|\\bkasus\\b",
    RegexOption.IGNORE_CASE
)

private val PARA_SPLIT = Regex("\\n\\s*\\n")
private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")

/**
 * Teks yang sedang mengalir terjebak dalam pengulangan: banyak paragraf pendek berurutan yang diawali kata sama dan berkosakata mirip
 * (mis. "Saya cari harga ...", "Saya mulai dengan pencarian ..." tanpa henti), dan belum ada blok pemanggilan alat.
 * Paragraf terakhir diabaikan karena mungkin masih ditulis.
 */
internal fun isRepetitionLoop(text: String): Boolean {
    if (text.length < 500 || text.contains(Protocol.OPEN)) return false
    if (hasRepeatedSegments(text)) return true
    val paras = text.split(PARA_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
    if (paras.size < 8) return false
    val tail = paras.dropLast(1).takeLast(6)
    if (tail.any { it.length > 300 }) return false
    val words = tail.map { p -> p.lowercase().split(WORD_SPLIT).filter { it.isNotEmpty() } }
    val sets = words.map { w -> w.filter { it.length > 2 }.toSet() }
    var sum = 0.0
    for (i in 1 until sets.size) {
        val union = (sets[i - 1] union sets[i]).size
        if (union > 0) sum += (sets[i - 1] intersect sets[i]).size.toDouble() / union
    }
    val mean = sum / (sets.size - 1)
    val firstWord = words.mapNotNull { it.firstOrNull() }.groupingBy { it }.eachCount().maxOfOrNull { it.value } ?: 0
    return mean >= 0.5 || (firstWord >= 5 && mean >= 0.15)
}
private val SEGMENT_SPLIT = Regex("[\\n.!?]+")
private val SPACES = Regex("\\s+")

/**
 * Kalimat/baris identik yang berulang di ujung teks, apa pun pemisahnya (satu baris baru, baris kosong, atau hanya titik).
 * Pemeriksaan paragraf di bawah butuh baris kosong antar paragraf, sehingga pengulangan yang ditulis tanpa jarak lolos darinya.
 * Segmen terakhir diabaikan karena mungkin masih ditulis; segmen pendek (< 15 huruf, mis. "}" atau "return") tidak dihitung.
 */
internal fun hasRepeatedSegments(text: String): Boolean {
    val segs = text.split(SEGMENT_SPLIT).map { it.trim().lowercase().replace(SPACES, " ") }.filter { it.isNotEmpty() }
    if (segs.size < 6) return false
    val tail = segs.dropLast(1).takeLast(8)
    val top = tail.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return false
    return top.value >= 4 && top.key.length >= 15
}
private const val MAX_NUDGES = 2
private const val MAX_PERSIST_BUILD = 8   // dorongan lanjut saat build terakhir gagal
private const val MAX_PERSIST_TEXT = 3    // dorongan lanjut bila hanya teksnya yang mengaku belum berhasil
private const val MAX_EXTENSIONS = 3
private const val EXTENSION_STEPS = 60
private const val MAX_PARALLEL_TASKS = 3      // sub-agen yang boleh memanggil model bersamaan (jaga batas laju kunci API di ponsel)
/** Alat yang hasilnya membawa konten pihak luar ke konteks; sukses berarti sesi tercemar (taint). */
internal val TAINTING_TOOLS = setOf("WebFetch", "WebSearch", "GithubIssues", "GmailSearchRead", "GithubDownloadArtifact")

/** Normalisasi path relatif (menyatukan '\', membuang './' serta menyederhanakan '..') sebelum pemeriksaan keamanan. */
internal fun normalizeRelPath(rawPath: String): String {
    val parts = ArrayList<String>()
    for (seg in rawPath.trim().replace('\\', '/').split('/')) {
        when (seg) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.lastIndex) else parts.add("..")
            else -> parts.add(seg)
        }
    }
    return parts.joinToString("/")
}

/**
 * Menyaring teks tugas agar hanya URL yang benar-benar diketik pengguna (bukan balasan `Mythos:` terdahulu
 * atau blok lampiran/catatan otomatis `uploads/_catatan.md`) yang ditandai tepercaya di [WebFetchGuard].
 */
internal fun userOnlyTextForTrust(task: String): String {
    fun stripAttachmentBlocks(s: String): String {
        var out = s
        for (marker in listOf("\n\nLAMPIRAN DI PROYEK INI:", "\n\nLAMPIRAN DISIMPAN DI PROYEK:", "\n\nCATATAN FOTO (uploads/_catatan.md")) {
            val idx = out.indexOf(marker)
            if (idx >= 0) out = out.substring(0, idx)
        }
        return out
    }
    val cleaned = stripAttachmentBlocks(task)
    val newMsgMarker = "\nPesan baru dari pengguna:\n"
    if (!cleaned.startsWith("Percakapan sebelumnya (paling lama di atas):\n") || !cleaned.contains(newMsgMarker)) {
        return cleaned
    }
    val beforeNew = cleaned.substringBefore(newMsgMarker)
    val afterNew = cleaned.substringAfter(newMsgMarker)
    val userLines = StringBuilder()
    var inUser = false
    for (line in beforeNew.split('\n')) {
        when {
            line.startsWith("Pengguna: ") -> {
                inUser = true
                userLines.append(line.removePrefix("Pengguna: ")).append('\n')
            }
            line.startsWith("Mythos: ") -> inUser = false
            inUser -> userLines.append(line).append('\n')
        }
    }
    userLines.append(afterNew)
    return stripAttachmentBlocks(userLines.toString())
}

/** Berkas yang ikut dieksekusi saat build/CI (bukan hanya *.sh dan *.gradle), sehingga penulisannya saat tercemar perlu persetujuan. */
internal fun isBuildSensitivePath(path: String): Boolean {
    val p = normalizeRelPath(path).lowercase()
    if (p.isEmpty()) return false
    val name = p.substringAfterLast('/')
    val isTestDir = p.startsWith("src/test/") || p.contains("/src/test/") ||
        p.startsWith("src/androidtest/") || p.contains("/src/androidtest/")
    val isTestSource = isTestDir && (name.endsWith(".kt") || name.endsWith(".java"))
    return p == ".github" || p.startsWith(".github/") || p.contains("/.github/") ||
        p == "buildsrc" || p.startsWith("buildsrc/") || p.contains("/buildsrc/") ||
        p == "draf" || p.startsWith("draf/") || p.contains("/draf/") ||
        name.endsWith(".sh") || name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.endsWith(".properties") ||
        name in setOf("gradlew", "gradlew.bat", "makefile", "package.json", "build.xml", "pom.xml", "dockerfile", "justfile") ||
        isTestSource
}

private const val MAX_TASKS_PER_TURN = 6      // sub-agen maksimal dalam satu balasan

private const val KEEP_GOING_HINT = "The goal is NOT achieved yet (the last GithubBuild failed, or your own reply says it is unfinished/failed). " +
    "Do not stop, do not report failure, and do not wait for the user to repeat the request. Read the failure log, find the root cause, " +
    "fix it with Edit/Write, then run GithubBuild again, and repeat until the build is green. Never retry the same fix twice. " +
    "Only if something strictly needs the user (a token, a permission, a decision) ask with AskUserQuestion. Reply in plain text only when the goal is really achieved."

private val INCOMPLETE = Regex("\\b(belum (berhasil|selesai|bisa|sukses|hijau|lolos)|tidak berhasil|gagal|masih (error|gagal|salah|bermasalah)|not yet|still fail(s|ing)?|failed)\\b")
private val URL_IN_TEXT = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

/** Apakah balasan tanpa alat ini harus ditolak sebagai "berhenti terlalu dini". Pertanyaan ke pengguna selalu boleh mengakhiri giliran. */
internal fun shouldKeepGoingFor(buildFailed: Boolean, persist: Int, text: String, usedTools: Boolean = true): Boolean {
    val t = text.trim().lowercase().replace(URL_IN_TEXT, "")
    if (t.contains('?')) return false
    if (buildFailed) return persist < MAX_PERSIST_BUILD
    // PATCH7: tanpa satu pun alat dipanggil, kata "belum bisa/gagal" hanyalah laporan keterbatasan, bukan pekerjaan setengah jalan.
    // Memaksa ulang hanya membuat model mengetik jawaban yang sama berulang-ulang.
    return usedTools && persist < MAX_PERSIST_TEXT && INCOMPLETE.containsMatchIn(t)
}

private val NORM = Regex("[^\\p{L}\\p{N}]+")

/** PATCH7: dua balasan dianggap sama bila isinya sama setelah tanda baca/huruf besar diabaikan. */
internal fun sameReply(a: String, b: String): Boolean {
    if (a.isBlank() || b.isBlank()) return false
    return a.lowercase().replace(NORM, " ").trim() == b.lowercase().replace(NORM, " ").trim()
}

private val TOOL_CLAIM = Regex(
    "(alat|tool|fitur)[^.\\n]{0,60}(tidak tersedia|tidak ada|tidak dapat diakses|unavailable|not available)|" +
        "(tidak|belum) (bisa|dapat) (membaca|memeriksa|membuka|mengakses|melihat|mengubah|mengedit|menerapkan)[^.\\n]{0,40}(lampiran|berkas|file|zip|arsip|proyek|source|kode|patch)|" +
        "(alat|tool)[^.\\n]{0,60}(tidak (tampil|muncul|aktif)|tidak dapat dijalankan)|" +
        "(sesi|lingkungan) (ini|yang aktif) (tidak|belum) (dapat|bisa) menjalankan",
    RegexOption.IGNORE_CASE
)

/** PATCH7: balasan tanpa alat yang mengaku alat/lampiran tidak bisa diakses. */
internal fun claimsToolsUnavailable(text: String): Boolean = TOOL_CLAIM.containsMatchIn(text)

private val INTENT_STARTERS = listOf(
    "saya akan", "aku akan", "baik, saya akan", "baik saya akan", "baik, aku akan", "mari saya", "mari kita", "sekarang saya",
    "selanjutnya saya", "pertama, saya", "saya perlu", "saya coba", "saya mulai",
    "i will", "i'll", "let me", "let's", "now i", "next, i", "first, i", "i need to", "i am going to",
)

private val TRUNCATED_HINT = "Your last reply was cut off by the output limit (roughly 4,000 tokens), so its tool call was lost. " +
    "Do NOT resend the same large call. Split the work into small calls: Write only the first ~120 lines of the file now (a valid, self-contained prefix), " +
    "then add the next parts in later replies with Edit (anchor on the last line you wrote), or divide the code over several smaller files. " +
    "Keep every tool call under ~150 lines."

// Balasan yang jelas-jelas penutup / permintaan ke pengguna / menunggu bukan "niat yang belum dijalankan".
private val FINAL_START = Regex("^(selesai|beres|berhasil|done|completed|finished|all set)\\s*[.!]")
private val ASKS_USER = Regex(
    "\\b(saya|aku) (perlu|butuh|ingin) (tahu|mengetahui|informasi|konfirmasi|detail|penjelasan|petunjuk|klarifikasi)\\b|" +
        "\\b(tolong|mohon|silakan|please) (beri|berikan|kirim|kirimkan|jelaskan|konfirmasi|provide|tell|confirm|send)\\b|" +
        "\\b(beri tahu|beritahu|kabari) saya\\b|\\blet me know\\b|\\bi need (to know|more information|your)\\b"
)
private val WAITING = Regex("\\bmenunggu (instruksi|perintah|jawaban|konfirmasi|balasan|arahan|permintaan|respons)|\\b(siap membantu|standby)\\b|\\b(awaiting|waiting for|wait for) (your|further|new)\\b")

private val GREETING_PREFIX = Regex("^(halo|hai|hi|hello|oke|ok|baik|baiklah|siap|tentu)(\\s+(bos|boss|bro|kak|min))?\\s*[,.!:]\\s*")
// PATCH30: Codex sering menulis niat dalam bentuk kini ("Saya lanjutkan ...", "Saya membaca ... lalu ...") lalu berhenti tanpa alat.
private val INTENT_PRESENT = Regex(
    "\\b(saya|aku)\\s+(lanjutkan|lanjut|teruskan|cari|cek|periksa|baca|buka|lihat|tinjau|jalankan|telusuri)\\b|" +
        "\\b(saya|aku)\\s+(membaca|memeriksa|mengecek|membuka|mencari|meninjau)\\b[^.\\n]{0,120}\\b(lalu|kemudian|setelah itu|untuk menentukan)\\b"
)
private val INTENT_ANYWHERE = Regex("\\b(saya|aku|i)\\s+(akan|mau|coba|mulai|perlu|segera|will|am going to|need to)\\b|\\b(i'll|let me|let's|mari kita|mari saya)\\b")

/**
 * Balasan pendek yang hanya menyatakan niat (bukan hasil, bukan pertanyaan) dan tidak memanggil alat.
 * Sapaan di depan ("Halo BOS, saya akan ...") diabaikan, dan niat di kalimat mana pun pada balasan pendek ikut dihitung.
 */
internal fun looksLikeUnfulfilledIntent(text: String, noToolsYet: Boolean = false): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 600 || t.contains('?') || t.contains("```")) return false
    var s = t.lowercase()
    repeat(2) { s = s.replace(GREETING_PREFIX, "") }
    if (FINAL_START.containsMatchIn(s) || ASKS_USER.containsMatchIn(s) || WAITING.containsMatchIn(s)) return false
    // Belum ada alat yang jalan di tugas ini + balasan pendek tanpa pertanyaan = hampir pasti janji, apa pun kata-katanya
    // ("saya cari", "saya cek", "sedang saya periksa", dst). Tidak bergantung pada daftar kata kerja.
    if (noToolsYet) return true
    if (INTENT_STARTERS.any { s.startsWith(it) } || s.endsWith(":")) return true
    return s.length <= 300 && (INTENT_ANYWHERE.containsMatchIn(s) || INTENT_PRESENT.containsMatchIn(s))
}

/** Kalimat pembuka cadangan bila model langsung memanggil alat tanpa menyapa dulu. */
internal fun openingLine(address: String?): String =
    if (address.isNullOrBlank()) "Baik, saya mulai mengerjakannya." else "Baik ${address.trim()}, saya mulai mengerjakannya."

/** Batas isi workflow yang ditampilkan utuh pada dialog persetujuan. */
internal const val MAX_WORKFLOW_REVIEW_CHARS = 20_000
