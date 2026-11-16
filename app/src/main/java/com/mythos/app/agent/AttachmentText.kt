package com.mythos.app.agent

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Bagian lampiran yang murni JVM (bisa diuji tanpa Android): nama aman, deteksi teks, ekstraksi Office, unzip aman. */
object AttachmentText {

    fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trimStart('.')
        return cleaned.ifEmpty { "lampiran" }.take(80)
    }

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** Teks bila tidak ada byte NUL pada 4 KB pertama dan dapat didekode UTF-8 dengan sedikit karakter pengganti. */
    fun isProbablyText(bytes: ByteArray): Boolean {
        val head = bytes.copyOf(minOf(bytes.size, 4096))
        if (head.any { it == 0.toByte() }) return false
        val s = String(head, Charsets.UTF_8)
        return s.count { it == '\uFFFD' } <= head.size / 50
    }

    private val XML_ENTITY = Regex("&(?:#(x|X)?([0-9a-fA-F]+)|([a-zA-Z]+));")

    private fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return XML_ENTITY.replace(s) { m ->
            val num = m.groupValues[2]
            if (num.isNotEmpty()) {
                val cp = num.toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
                if (cp != null && cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else m.value
            } else {
                when (m.groupValues[3]) {
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "amp" -> "&"
                    else -> m.value
                }
            }
        }
    }

    /** Batas arsip Office: jumlah entri yang diperiksa dan total byte hasil dekompresi (semua entri, termasuk yang dilewati). */
    internal const val MAX_ZIP_ENTRIES = 5_000
    internal const val MAX_ZIP_TOTAL_BYTES = 100_000_000L
    private const val MAX_ZIP_WANTED_ENTRY_BYTES = 20_000_000

    /**
     * Baca entri yang diminta dari arsip. Tiga batas melawan zip bomb: tiap entri yang dibaca <= 20 MB, jumlah entri <= [maxEntries],
     * dan total byte dekompresi <= [maxTotalBytes]. Entri yang tidak diminta TETAP dialirkan dan dihitung (ZipInputStream harus
     * menginflasinya untuk melewatinya), supaya ribuan entri kecil atau satu entri raksasa tidak menguras CPU/memori tanpa terhitung.
     */
    internal fun zipEntries(
        bytes: ByteArray,
        maxEntries: Int = MAX_ZIP_ENTRIES,
        maxTotalBytes: Long = MAX_ZIP_TOTAL_BYTES,
        wanted: (String) -> Boolean,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var entries = 0
        var grandTotal = 0L
        val tmp = ByteArray(8192)
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (++entries > maxEntries) throw IOException("Arsip memuat terlalu banyak berkas")
                val keep = !e.isDirectory && wanted(e.name)
                val buf = if (keep) java.io.ByteArrayOutputStream() else null
                var entryTotal = 0
                while (true) {
                    val n = z.read(tmp)
                    if (n < 0) break
                    entryTotal += n
                    grandTotal += n
                    if (grandTotal > maxTotalBytes) throw IOException("Isi arsip terlalu besar")
                    if (keep) {
                        if (entryTotal > MAX_ZIP_WANTED_ENTRY_BYTES) throw IOException("Isi dokumen terlalu besar")
                        buf!!.write(tmp, 0, n)
                    }
                }
                if (keep) out[e.name] = buf!!.toString("UTF-8")
            }
        }
        return out
    }

    /** Pasangan `<tag ...>isi</tag>` atau `<tag .../>` (self-closing -> inner = ""), dipindai secara linear O(N). */
    internal class XmlElem(val attrs: String, val inner: String)

    internal fun findXmlElements(xml: String, tag: String): List<XmlElem> {
        val out = ArrayList<XmlElem>()
        val n = xml.length
        val open = "<$tag"
        val close = "</$tag>"
        var i = xml.indexOf(open)
        while (i >= 0) {
            val after = i + open.length
            if (after < n && (xml[after].isLetterOrDigit() || xml[after] == '_' || xml[after] == ':' || xml[after] == '-')) {
                i = xml.indexOf(open, after)
                continue
            }
            val gt = xml.indexOf('>', after)
            if (gt < 0) break
            if (xml[gt - 1] == '/') {
                out.add(XmlElem(xml.substring(after, gt - 1), ""))
                i = xml.indexOf(open, gt + 1)
                continue
            }
            val end = xml.indexOf(close, gt + 1)
            if (end < 0) break
            out.add(XmlElem(xml.substring(after, gt), xml.substring(gt + 1, end)))
            i = xml.indexOf(open, end + close.length)
        }
        return out
    }

    /** Ekstrak teks dari .docx / .pptx / .xlsx secara linear O(N); null bila ekstensi lain atau arsip rusak. */
    fun extractOfficeText(bytes: ByteArray, ext: String): String? {
        return try {
        when (ext) {
            "docx" -> {
                val xml = zipEntries(bytes) { it == "word/document.xml" }["word/document.xml"] ?: return null
                unescape(
                    stripTags(
                        xml.replace(Regex("</w:p>\\s*</w:tc>"), "\t")   // akhir sel tabel -> TAB, supaya kolom tidak menempel
                            .replace("</w:tr>", "\n")
                            .replace("</w:p>", "\n").replace("<w:tab/>", "\t").replace(Regex("<w:br[^/>]*/>"), "\n"),
                        ""
                    )
                ).lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
            }
            "pptx" -> {
                val slides = zipEntries(bytes) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                    .entries.sortedBy { Regex("(\\d+)").find(it.key.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0 }
                slides.mapIndexed { i, (_, xml) ->
                    val paras = findXmlElements(xml, "a:p").map { p ->
                        findXmlElements(p.inner, "a:t").joinToString("") { unescape(it.inner) }
                    }.filter { it.isNotEmpty() }
                    val t = if (paras.isNotEmpty()) paras.joinToString("\n")
                    else findXmlElements(xml, "a:t").joinToString("\n") { unescape(it.inner) }
                    "--- Slide ${i + 1} ---\n$t"
                }.joinToString("\n\n").trim()
            }
            "xlsx" -> {
                val files = zipEntries(bytes) { it == "xl/sharedStrings.xml" || (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml")) }
                val shared = findXmlElements(files["xl/sharedStrings.xml"].orEmpty(), "si")
                    .map { si -> unescape(findXmlElements(si.inner, "t").joinToString("") { it.inner }) }
                // urutan alami: sheet2 sebelum sheet10 (urutan abjad menaruh sheet10 lebih dulu)
                files.filterKeys { it.startsWith("xl/worksheets/") }.entries
                    .sortedBy { Regex("(\\d+)").find(it.key.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0 }
                    .joinToString("\n\n") { (name, xml) ->
                    // <row .../> yang self-closing tidak boleh "menelan" baris sesudahnya
                    val rows = findXmlElements(xml, "row").joinToString("\n") { r ->
                        findXmlElements(r.inner, "c").joinToString("\t") { c ->
                            val attrs = c.attrs
                            val inner = c.inner
                            if (attrs.contains("t=\"inlineStr\"")) {
                                unescape(findXmlElements(inner, "t").joinToString("") { it.inner })
                            } else {
                                val v = findXmlElements(inner, "v").firstOrNull()?.inner.orEmpty()
                                if (attrs.contains("t=\"s\"")) shared.getOrElse(v.toIntOrNull() ?: -1) { "" } else unescape(v)
                            }
                        }
                    }
                    "--- ${name.substringAfterLast('/').removeSuffix(".xml")} ---\n$rows"
                }.trim()
            }
            else -> null
        }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    class UnzipResult(val files: Int, val bytes: Long)

    /** Ekstrak zip ke [dest] dengan perlindungan zip-slip, batas jumlah file dan total byte. */
    fun unzipSafely(zip: File, dest: File, maxFiles: Int = 500, maxBytes: Long = 50_000_000L): UnzipResult {
        dest.mkdirs()
        val root = dest.canonicalFile
        var files = 0
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val target = File(root, e.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator) && target != root) throw IOException("Zip tidak aman: ${e.name}")
                if (e.isDirectory) {
                    target.mkdirs()
                    continue
                }
                if (++files > maxFiles) throw IOException("Zip berisi lebih dari $maxFiles file")
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("Isi zip melebihi ${maxBytes / 1_000_000} MB")
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        return UnzipResult(files, total)
    }
}
