package com.mythos.app.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.sp

/*
 * Sistem tipografi terpusat (Inter, sans-serif, rata kiri, baris stabil seperti aplikasi percakapan modern di HP).
 *
 * Dua lapis:
 *  - [TypeScale]  : angka murni (ukuran sp, rasio tinggi baris, jarak dp, aturan jarak antarblok). Tanpa dependensi Compose,
 *                   jadi bisa dites di JVM biasa.
 *  - [MythosType] : membangun TextStyle/Typography dari angka di atas. Satu-satunya tempat letter-spacing, line-break,
 *                   lineHeightStyle, dan fitur OpenType ditetapkan.
 *
 * Aturan: komponen TIDAK menulis fontSize/lineHeight/letterSpacing sendiri untuk teks percakapan; ia memanggil MythosType.
 */

/** Angka murni sistem tipografi. Ukuran dalam sp, jarak dalam dp, rasio = lineHeight / fontSize. */
internal object TypeScale {
    // ---- ukuran dan rasio tinggi baris
    const val BODY = 16f
    const val BODY_LINE = 1.6f           // 16sp -> 25,6sp

    const val H1 = 24f                   // judul utama
    const val H2 = 20f                   // subjudul
    const val H3 = 18f                   // subjudul kecil
    const val H4 = 16f                   // h4..h6: seukuran isi, tebal

    const val TABLE = 14f
    const val TABLE_LINE = 1.5f
    const val CODE_INLINE = 14f          // `kode` di tengah kalimat (sedikit lebih kecil dari isi agar sejajar optik)
    const val CODE_BLOCK = 13f
    const val CAPTION = 12.5f
    const val CAPTION_LINE = 1.45f
    const val INPUT_LINE = 1.5f          // kolom input: seukuran isi, baris sedikit lebih rapat

    fun headingSize(level: Int): Float = when (level) { 1 -> H1; 2 -> H2; 3 -> H3; else -> H4 }
    fun headingLine(level: Int): Float = when (level) { 1 -> 1.3f; 2 -> 1.35f; 3 -> 1.4f; else -> BODY_LINE }

    // ---- jarak vertikal antarblok (dp)
    const val PARAGRAPH_GAP = 14f        // paragraf ke paragraf (rentang yang dituju 14-16)
    const val BLOCK_GAP = 16f            // sekitar tabel dan kutipan
    const val LIST_ENTRY_GAP = 10f       // paragraf -> butir pertama
    const val LIST_ITEM_GAP = 6f         // butir -> butir
    const val LIST_NESTED_GAP = 4f       // butir -> anak butir
    const val HEADING_AFTER = 8f         // judul -> isi di bawahnya (judul menempel ke isinya)
    const val H1_BEFORE = 28f
    const val H_BEFORE = 22f             // h2, h3
    const val H4_BEFORE = 18f            // h4..h6

    // ---- daftar
    const val LIST_BASE_INDENT = 4f
    const val LIST_LEVEL_INDENT = 20f
    const val BULLET_COL = 22f           // lebar kolom penanda "•" (sudah termasuk celah ke teks)
    const val MARKER_GAP = 6f            // celah penanda -> teks, di dalam kolom

    fun listIndentDp(level: Int): Float = LIST_BASE_INDENT + level.coerceIn(0, 3) * LIST_LEVEL_INDENT

    /** Lebar kolom penanda bernomor menurut panjang penanda terpanjang di daftarnya ("9." = 2, "10." = 3). */
    fun numberedColumnDp(maxMarkerLen: Int): Float = when {
        maxMarkerLen <= 2 -> 24f
        maxMarkerLen == 3 -> 30f
        else -> 36f
    }

    /**
     * Jarak di atas blok [cur] bila blok sebelumnya [prev]. Satu fungsi untuk seluruh jawaban supaya ritme vertikal konsisten:
     * judul menjauh dari isi di atasnya tetapi menempel ke isi di bawahnya; butir daftar rapat; sisanya 14-16 dp.
     */
    fun gapDp(prev: ProseBlock?, cur: ProseBlock): Float = when {
        prev == null -> 0f
        cur is ProseBlock.Heading ->
            if (prev is ProseBlock.Heading) HEADING_AFTER
            else when (cur.level) { 1 -> H1_BEFORE; 2, 3 -> H_BEFORE; else -> H4_BEFORE }
        prev is ProseBlock.Heading -> HEADING_AFTER
        cur is ProseBlock.Item && prev is ProseBlock.Item -> if (cur.level > prev.level) LIST_NESTED_GAP else LIST_ITEM_GAP
        cur is ProseBlock.Item -> LIST_ENTRY_GAP
        cur is ProseBlock.Table || cur is ProseBlock.Quote || prev is ProseBlock.Table || prev is ProseBlock.Quote -> BLOCK_GAP
        else -> PARAGRAPH_GAP
    }

    /**
     * Lebar kolom penanda tiap butir (dp), per indeks blok; 0 untuk blok yang bukan butir. Daftar bernomor satu rangkaian memakai
     * SATU lebar (menurut nomor terpanjangnya) supaya teks butir 9 dan butir 10 tetap lurus.
     */
    fun markerColumnsDp(blocks: List<ProseBlock>): List<Float> {
        val out = FloatArray(blocks.size)
        var i = 0
        while (i < blocks.size) {
            if (blocks[i] !is ProseBlock.Item) { i++; continue }
            var j = i
            var maxLen = 0
            while (j < blocks.size && blocks[j] is ProseBlock.Item) {
                val m = (blocks[j] as ProseBlock.Item).marker
                if (m != "•") maxLen = maxOf(maxLen, m.length)
                j++
            }
            val numbered = numberedColumnDp(maxLen)
            for (k in i until j) out[k] = if ((blocks[k] as ProseBlock.Item).marker == "•") BULLET_COL else numbered
            i = j
        }
        return out.toList()
    }
}

/** Pembangun gaya Compose dari [TypeScale]. Keluarga font dibaca dari [MythosSans] (state), jadi ganti gaya font berlaku seketika. */
internal object MythosType {
    /** Garis dasar konsisten: ruang tambahan baris dibagi rata atas-bawah dan tidak dipangkas, sehingga campuran ukuran dalam satu baris tetap sejajar. */
    val lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )

    /** Kerning dan ligatur standar; kolom angka di tabel memakai angka tabular supaya sejajar. */
    const val FEATURES = "kern, liga, calt"
    const val FEATURES_TABULAR = "kern, tnum"

    /** Gaya dasar semua teks percakapan: rata kiri, tanpa letter-spacing, pemenggalan baris serakah (stabil saat teks mengalir), tanpa tanda hubung otomatis. */
    fun text(
        size: Float,
        lineRatio: Float,
        weight: FontWeight = FontWeight.Normal,
        color: Color = Color.Unspecified,
        features: String = FEATURES,
    ): TextStyle = TextStyle(
        fontFamily = MythosSans,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size * lineRatio).sp,
        letterSpacing = 0.sp,
        textAlign = TextAlign.Start,
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None,
        textMotion = TextMotion.Animated,
        lineHeightStyle = lineHeightStyle,
        fontFeatureSettings = features,
        color = color,
    )

    fun body(color: Color = Color.Unspecified): TextStyle = text(TypeScale.BODY, TypeScale.BODY_LINE, FontWeight.Normal, color)

    fun input(color: Color): TextStyle = text(TypeScale.BODY, TypeScale.INPUT_LINE, FontWeight.Normal, color)

    fun caption(color: Color = Color.Unspecified): TextStyle = text(TypeScale.CAPTION, TypeScale.CAPTION_LINE, FontWeight.Normal, color)

    fun table(header: Boolean, color: Color): TextStyle =
        text(TypeScale.TABLE, TypeScale.TABLE_LINE, if (header) FontWeight.SemiBold else FontWeight.Normal, color, FEATURES_TABULAR)

    /** Judul h1..h6 di atas gaya isi [base] (warna dan keluarga font ikut [base]). Semua SemiBold (600). */
    fun heading(base: TextStyle, level: Int): TextStyle {
        val size = TypeScale.headingSize(level)
        return base.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = size.sp,
            lineHeight = (size * TypeScale.headingLine(level)).sp,
        )
    }

    /** Typography Material: SEMUA peran memakai Inter pilihan, letter-spacing 0 (bawaan Material bodyLarge 0,5sp membuat teks renggang). */
    fun materialTypography(): Typography = Typography(
        displayLarge = text(32f, 1.25f, FontWeight.SemiBold),
        displayMedium = text(28f, 1.25f, FontWeight.SemiBold),
        displaySmall = text(24f, 1.3f, FontWeight.SemiBold),
        headlineLarge = text(TypeScale.H1, 1.3f, FontWeight.SemiBold),
        headlineMedium = text(TypeScale.H2, 1.35f, FontWeight.SemiBold),
        headlineSmall = text(TypeScale.H3, 1.4f, FontWeight.SemiBold),
        titleLarge = text(20f, 1.35f, FontWeight.Medium),
        titleMedium = text(16f, 1.5f, FontWeight.Medium),
        titleSmall = text(14f, 1.45f, FontWeight.Medium),
        bodyLarge = text(TypeScale.BODY, TypeScale.BODY_LINE, FontWeight.Normal),
        bodyMedium = text(14f, 1.5f, FontWeight.Normal),
        bodySmall = text(12f, 1.5f, FontWeight.Normal),
        labelLarge = text(14f, 1.45f, FontWeight.Medium),
        labelMedium = text(12f, 1.4f, FontWeight.Medium),
        labelSmall = text(11f, 1.4f, FontWeight.Medium),
    )
}
