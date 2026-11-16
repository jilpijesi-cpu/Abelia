package com.mythos.app.agent

import kotlin.math.abs

/**
 * patch13: pengaman putaran WebFetch. Situs berbasis JavaScript sering mengembalikan halaman yang SAMA untuk alamat apa pun
 * (/login, /daftar, /register -> beranda). Penjaga duplikat di Agent hanya menangkap URL yang persis sama, jadi model terus
 * menebak alamat baru sampai batas 300 langkah. Di sini: (1) halaman yang hampir identik dengan yang sudah dibuka diberi catatan,
 * (2) jumlah alamat berbeda per situs dibatasi per tugas. Satu instance per tugas ([Agent.RunState]).
 */
internal class WebFetchGuard(
    private val softLimit: Int = 5,
    private val hardLimit: Int = 8,
    private val similarLimit: Int = 3,
) {
    private class Page(val url: String, val title: String, val total: Int)

    private val urls = HashMap<String, LinkedHashSet<String>>()   // situs -> alamat berbeda yang sudah dicoba
    private val pages = HashMap<String, MutableList<Page>>()      // situs -> halaman yang berhasil dibuka
    private val similarHits = HashMap<String, Int>()              // situs -> berapa kali halaman baru ternyata mirip yang lama

    private val trustedUrls = HashSet<String>()                // URL spesifik dari tugas pengguna/hasil WebSearch
    private val openedUrls = HashSet<String>()                 // URL spesifik yang sudah dibuka dalam tugas ini

    /** Daftarkan URL spesifik dari teks tepercaya; domain/path lain tidak otomatis dipercaya. */
    fun trustFromText(text: String) {
        for (m in URL_IN_TEXT.findAll(text)) exactUrlKey(m.value)?.let { trustedUrls.add(it) }
    }

    /**
     * Pencegahan eksfiltrasi: setelah konten eksternal masuk konteks, hanya URL spesifik yang sudah dibuka atau disebut
     * langsung oleh pengguna/hasil WebSearch yang boleh diulang tanpa persetujuan. Domain tepercaya (termasuk GitHub)
     * tidak otomatis membuat semua path-nya tepercaya.
     */
    fun exfilRisk(rawUrl: String): String? {
        val (site, _) = parse(rawUrl) ?: return null
        var u = rawUrl.trim()
        if (!u.contains("://")) u = "https://$u"
        val noFragment = u.substringBefore('#')
        val authority = noFragment.substringAfter("://").substringBefore('/').substringBefore('?')
        if ('@' in authority) return "URL memuat kredensial/userinfo (@)"
        val query = noFragment.substringAfter('?', "")
        if (query.isNotEmpty()) return "URL membawa query string"
        val exact = exactUrlKey(rawUrl) ?: return "URL tidak valid setelah konten eksternal dibaca"
        if (exact in trustedUrls || exact in openedUrls) return null
        val path = noFragment.substringAfter("://").substringBefore('?').substringAfter('/', "")
        if (path.length > MAX_PATH_AFTER_TAINT) return "path URL sangat panjang (${path.length} karakter)"
        return "alamat spesifik di $site belum disebut pengguna/hasil pencarian dan belum pernah dibuka; persetujuan diperlukan"
    }

    /** Pesan penolakan bila alamat BARU di situs ini tidak boleh dibuka lagi; null bila boleh. Alamat yang sama (lanjutan offset) selalu boleh. */
    fun before(rawUrl: String): String? {
        val (site, key) = parse(rawUrl) ?: return null
        val seen = urls[site]
        if (seen != null && key in seen) return null
        if ((similarHits[site] ?: 0) >= similarLimit) {
            return "WebFetch refused: several different addresses on $site already returned the same page, so this site serves one page for any path. " +
                "Do not guess more URLs. Use the links actually listed on a page you opened, use WebSearch for another source, or answer with what you have."
        }
        // Membaca repo GitHub berarti satu alamat per file: batas jumlah alamat tidak berlaku di sini (pengaman halaman kembar di atas tetap).
        if (site !in UNLIMITED_SITES && (seen?.size ?: 0) >= hardLimit) {
            return "WebFetch refused: you already tried ${seen!!.size} different addresses on $site in this task. Stop opening more pages there. " +
                "Answer with what you found (say which URLs you actually opened), or use WebSearch for a different source."
        }
        return null
    }

    /** Dipanggil sesudah WebFetch dijalankan (berhasil atau gagal). Mengembalikan catatan sistem untuk ditempel di hasil, atau "". */
    fun after(rawUrl: String, ok: Boolean, text: String): String {
        val (site, key) = parse(rawUrl) ?: return ""
        val firstTime = urls.getOrPut(site) { LinkedHashSet() }.add(key)
        if (ok) exactUrlKey(rawUrl)?.let { openedUrls.add(it) }
        if (!ok || !firstTime) return ""
        val title = TITLE_RE.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val total = TOTAL_RE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: text.length
        val list = pages.getOrPut(site) { ArrayList() }
        val twin = list.firstOrNull { it.title.isNotEmpty() && it.title == title && abs(it.total - total) <= maxOf(50, it.total / 100) }
        list.add(Page(key, title, total))
        val count = urls.getValue(site).size
        return when {
            twin != null -> {
                similarHits[site] = (similarHits[site] ?: 0) + 1
                "\n\n[System note: this page is nearly identical to ${twin.url} (same title, same length). This site probably serves the same page for different addresses. " +
                    "Do not guess more URLs here; follow links actually listed on a page, or use WebSearch.]"
            }
            count >= softLimit && site !in UNLIMITED_SITES ->
                "\n\n[System note: you have now opened $count different addresses on $site. If nothing new is appearing, stop and answer with what you have.]"
            else -> ""
        }
    }

    companion object {
        private const val MAX_PATH_AFTER_TAINT = 120
        private val URL_IN_TEXT = Regex("""https?://[^\s<>"')\]]+""", RegexOption.IGNORE_CASE)
        private val TITLE_RE = Regex("""(?m)^Judul: (.*)$""")
        private val TOTAL_RE = Regex("""(?m)^Bagian: karakter \d+\S\d+ dari (\d+)""")
        /** Situs yang isinya memang banyak berkas berbeda (kode sumber): github.com (api.github.com) dan githubusercontent.com (raw.githubusercontent.com). */
        internal val UNLIMITED_SITES = setOf("github.com", "githubusercontent.com")
        private val TWO_PART_SLD = setOf("co", "com", "go", "or", "ac", "web", "sch", "net", "org")

        /** Kunci URL spesifik: host dinormalisasi, path dipertahankan (case-sensitive), fragmen diabaikan. */
        private fun exactUrlKey(rawUrl: String): String? {
            var u = rawUrl.trim()
            if (u.isEmpty()) return null
            if (!u.contains("://")) u = "https://$u"
            val noFragment = u.substringBefore('#')
            val scheme = noFragment.substringBefore("://").lowercase()
            if (scheme !in setOf("http", "https")) return null
            val rest = noFragment.substringAfter("://", "")
            val authority = rest.substringBefore('/').substringBefore('?')
            if (authority.isEmpty() || '@' in authority) return null
            val hostPort = authority.trimEnd('.').lowercase().removePrefix("www.")
            if (hostPort.isEmpty()) return null
            val suffix = rest.substringAfter('/', "")
            return "$hostPort/$suffix"
        }

        /** (situs, kunci alamat) atau null bila bukan alamat yang bisa dibaca. Situs = domain induk, jadi help.flip.id dan flip.id dihitung satu. */
        internal fun parse(rawUrl: String): Pair<String, String>? {
            var u = rawUrl.trim()
            if (u.isEmpty()) return null
            if (!u.contains("://")) u = "https://$u"
            val noFragment = u.substringBefore('#')
            val authority = noFragment.substringAfter("://").substringBefore('/').substringBefore('?')
            val host = authority.substringAfterLast('@').substringBefore(':').trimEnd('.').lowercase().removePrefix("www.")
            if (host.isEmpty()) return null
            val labels = host.split('.')
            val site = when {
                labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in TWO_PART_SLD -> labels.takeLast(3).joinToString(".")
                labels.size >= 2 -> labels.takeLast(2).joinToString(".")
                else -> host
            }
            val rest = noFragment.substringAfter("://").substringAfter('/', "")
            val key = "$host/$rest".trimEnd('/').lowercase()
            return site to key
        }
    }
}
