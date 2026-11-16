package com.mythos.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

/**
 * PATCH31: folder proyek Mythos ada di penyimpanan pribadi aplikasi dan tidak terjangkau Termux, sedangkan Codex native
 * bekerja di Termux. WorkspaceMirror menjembatani keduanya lewat soket yang sudah ada:
 *  - awal sesi: berkas proyek yang berubah dikirim ke runner, yang menulisnya ke ~/mythos-work/<id>/ dan bekerja di sana;
 *  - akhir sesi: runner mengirim balik berkas yang diubah/dibuat/dihapus Codex, lalu [applyBack] menulisnya ke proyek
 *    lewat [ProjectSandbox] (path di luar proyek ditolak).
 * Sinkronisasi hanya di batas sesi, bukan terus-menerus.
 */
class WorkspaceMirror(private val rootProvider: () -> File?) {
    /** path -> sha256 versi yang diketahui sudah ada di salinan Termux. */
    private val known = HashMap<String, String>()
    private val pending = HashMap<String, String>()

    /** Ringkasan hasil sinkron terakhir (untuk log/pesan); kosong bila tak ada yang perlu dilaporkan. */
    @Volatile var lastNote: String = ""
        private set

    /** Id folder di Termux (nama folder proyek), atau null bila belum ada proyek. */
    fun workspaceId(): String? = rootProvider()?.takeIf { it.isDirectory }?.name

    /** Bagian "workspace" untuk permintaan ke runner, atau null bila tidak ada proyek aktif. */
    @Synchronized
    fun buildRequest(): JSONObject? {
        val root = rootProvider()?.takeIf { it.isDirectory } ?: return null
        pending.clear()
        val files = JSONArray()
        var total = 0L
        var skipped = 0
        // Berkas kecil lebih dulu agar batas total tidak dihabiskan satu berkas besar.
        for ((rel, f) in WorkspaceFiles.scan(root).entries.sortedBy { it.value.length() }) {
            val size = f.length()
            if (size > MAX_FILE_BYTES || total + size > MAX_PUSH_BYTES) { skipped++; continue }
            val hash = WorkspaceFiles.sha256(f)
            if (known[rel] == hash) continue
            files.put(JSONObject().put("path", rel).put("b64", Base64.getEncoder().encodeToString(f.readBytes())))
            pending[rel] = hash
            total += size
        }
        lastNote = if (skipped > 0) "$skipped berkas terlalu besar tidak disalin ke Termux." else ""
        return JSONObject().put("id", root.name).put("files", files)
    }

    /** Dipanggil setelah sesi berhasil mengirim: berkas yang dikirim dianggap sudah ada di Termux. */
    @Synchronized
    fun commitPush() {
        known.putAll(pending)
        pending.clear()
    }

    /**
     * Tulis balik hasil kerja Codex ke proyek. [files] = path relatif -> isi; [deleted] = path yang dihapus Codex.
     * Mengembalikan ringkasan satu baris ("3 berkas diubah, 1 dihapus") atau string kosong.
     */
    @Synchronized
    fun applyBack(files: Map<String, ByteArray>, deleted: List<String>): String {
        val root = rootProvider()?.takeIf { it.isDirectory } ?: return ""
        val sandbox = ProjectSandbox(root)
        var written = 0
        var removed = 0
        var refused = 0
        for ((rel, bytes) in files) {
            val target = safeTarget(sandbox, rel)
            if (target == null) { refused++; continue }
            runCatching {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                known[rel] = WorkspaceFiles.sha256(target)
                written++
            }.onFailure { refused++ }
        }
        for (rel in deleted.take(MAX_DELETES)) {
            val target = safeTarget(sandbox, rel)
            if (target == null) { refused++; continue }
            if (target.isFile && runCatching { target.delete() }.getOrDefault(false)) {
                known.remove(rel)
                removed++
            }
        }
        val parts = buildList {
            if (written > 0) add("$written berkas diubah/dibuat")
            if (removed > 0) add("$removed dihapus")
            if (refused > 0) add("$refused ditolak")
        }
        return parts.joinToString(", ").also { if (it.isNotEmpty()) lastNote = it }
    }

    private fun safeTarget(sandbox: ProjectSandbox, rel: String): File? {
        val r = rel.trim().replace('\\', '/')
        if (r.isEmpty() || r.startsWith("/") || r.split('/').any { it == ".." } || WorkspaceFiles.isSensitivePath(r)) return null
        return try {
            sandbox.resolve(r).takeIf { it != sandbox.root }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val MAX_PUSH_BYTES = 24L * 1024 * 1024
        const val MAX_DELETES = 500
    }
}

internal object WorkspaceFiles {
    /** Folder yang tidak pernah disalin/dikembalikan (besar, dihasilkan mesin, atau berisi rahasia repo). */
    val SKIP_DIRS = setOf(
        ".git", "node_modules", "build", ".gradle", ".idea",
        ".mythos_codex", ".codex", ".ssh", ".aws", ".gnupg"
    )
    private val SECRET_BASENAMES = setOf(
        ".netrc", ".npmrc", ".pypirc", ".git-credentials", "local.properties",
        "keystore.properties", "google-services.json"
    )
    private val SECRET_FILE_PATTERNS = listOf(
        Regex("(?i)^\\.env(?:\\..*)?$"),
        Regex("(?i)^(?:auth|credentials|client_secret|service-account|firebase-adminsdk)[^/]*\\.json$"),
        Regex("(?i)^id_(?:rsa|ed25519|ecdsa|dsa)(?:\\.pub)?$"),
        Regex("(?i)^.*\\.(?:pem|key|p8|p12|pfx|jks|keystore|kdbx|asc)$")
    )
    private const val MAX_FILES = 5000
    private const val MAX_DEPTH = 14

    /** Path rahasia tidak boleh disalin ke Termux ataupun ditulis balik dari salinan Codex. */
    fun isSensitivePath(rel: String): Boolean {
        val parts = rel.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.any { it.lowercase() in SKIP_DIRS }) return true
        val base = parts.lastOrNull() ?: return true
        return base.lowercase() in SECRET_BASENAMES || SECRET_FILE_PATTERNS.any { it.matches(base) }
    }

    /** Semua berkas biasa di [root] (tanpa symlink dan folder/berkas rahasia): path relatif berpemisah "/" -> File. */
    fun scan(root: File): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        fun walk(dir: File, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (c in children) {
                if (out.size >= MAX_FILES) return
                if (Files.isSymbolicLink(c.toPath())) continue
                val rel = if (prefix.isEmpty()) c.name else "$prefix/${c.name}"
                if (c.isDirectory) {
                    if (c.name !in SKIP_DIRS) walk(c, rel, depth + 1)
                } else if (c.isFile && !isSensitivePath(rel)) {
                    out[rel] = c
                }
            }
        }
        walk(root, "", 0)
        return out
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
