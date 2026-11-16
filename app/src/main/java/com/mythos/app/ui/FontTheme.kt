package com.mythos.app.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.mythos.app.R

/**
 * Satu pilihan "Gaya font". [family] dibuat malas (lazy) supaya pengujian JVM yang hanya memeriksa kunci tidak menyentuh API Android.
 */
class FontOption(val key: String, val label: String, private val make: (Int) -> FontFamily) {
    private val cache = HashMap<Int, FontFamily>()

    /** Keluarga font dengan tambahan ketebalan [adj] (0 = asli). Hasilnya di-cache per nilai supaya geser slider tidak memuat ulang font. */
    fun familyFor(adj: Int): FontFamily = synchronized(cache) { cache.getOrPut(adj) { make(adj) } }

    val family: FontFamily get() = familyFor(0)
}

private val WEIGHT_BASES = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900)

/**
 * Font perangkat dengan kunci pencocokan [key] (bobot yang diminta teks, mis. Normal = 400) tetapi yang dimuat benar-benar
 * bobot [actual] (= key + tambahan ketebalan). Dulu keduanya disatukan lewat Font(DeviceFontFamilyName, FontWeight(key + adj)),
 * sehingga teks Normal (400) tetap memilih entri berbobot 400 dan slider "Ketebalan Font" tidak berefek pada font bawaan Android.
 */
@OptIn(ExperimentalTextApi::class)
private class ShiftedDeviceFont(private val name: String, key: Int, private val actual: Int) : AndroidFont(
    FontLoadingStrategy.Blocking,
    object : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface? = build(name, (font as ShiftedDeviceFont).actual)
        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? = loadBlocking(context, font)
    },
    // Bobot sebenarnya ikut dicatat di sini agar equals()/cache Compose membedakan keluarga dengan tambahan tebal berbeda
    // (tanpa ini, adj=0 dan adj=100 dianggap sama dan typeface lama dipakai ulang). Pemuat khusus kita mengabaikannya.
    FontVariation.Settings(FontVariation.weight(actual.coerceIn(1, 1000))),
) {
    override val weight: FontWeight = FontWeight(key)
    override val style: FontStyle = FontStyle.Normal

    companion object {
        fun build(name: String, w: Int): Typeface {
            val base = Typeface.create(name, Typeface.NORMAL)
            val weight = w.coerceIn(1, 1000)
            return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, false)
            else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}

@OptIn(ExperimentalTextApi::class)
private fun device(name: String, adj: Int = 0): FontFamily =
    FontFamily(WEIGHT_BASES.map { ShiftedDeviceFont(name, it, it + adj) })

/** Font bawaan Android: tanpa tambahan ketebalan dipakai apa adanya, dengan tambahan dipetakan ke font perangkat bernama [deviceName]. */
private fun generic(base: FontFamily, deviceName: String, adj: Int): FontFamily = if (adj == 0) base else device(deviceName, adj)

/** Inter (lisensi OFL) disertakan di res/font: satu keluarga font untuk seluruh aplikasi agar tulisan tidak bercampur. */
private fun inter(adj: Int = 0): FontFamily {
    val files = listOf(100 to R.font.inter_thin, 200 to R.font.inter_extralight, 300 to R.font.inter_light, 400 to R.font.inter_regular, 500 to R.font.inter_medium, 600 to R.font.inter_semibold, 700 to R.font.inter_bold)
    return FontFamily(WEIGHT_BASES.map { base ->
        // Bobot yang diminta digeser sebesar [adj], lalu dipetakan ke berkas Inter terdekat.
        val res = files.minByOrNull { kotlin.math.abs(it.first - (base + adj)) }!!.second
        Font(res, FontWeight(base))
    })
}

/**
 * Gaya font aplikasi (Pengaturan > Tampilan > Gaya font). Dibaca dari state Compose lewat [MythosSans], jadi seluruh layar
 * berganti seketika tanpa memulai ulang aplikasi. Semuanya memakai font bawaan Android (tanpa berkas font tambahan).
 */
object FontTheme {
    val options: List<FontOption> = listOf(
        FontOption("default", "Default (iOS)") { a -> inter(a) },
        FontOption("android", "Default (Android)") { a -> generic(FontFamily.SansSerif, "sans-serif", a) },
        FontOption("sistem", "Sistem") { a -> generic(FontFamily.Default, "sans-serif", a) },
        FontOption("ringkas", "Ringkas") { a -> device("sans-serif-condensed", a) },
        FontOption("tipis", "Tipis") { a -> device("sans-serif-light", a) },
        FontOption("tegas", "Tegas") { a -> device("sans-serif-medium", a) },
        FontOption("serif", "Serif (klasik)") { a -> generic(FontFamily.Serif, "serif", a) },
        FontOption("santai", "Santai") { a -> device("casual", a) },
        FontOption("tulisan", "Tulisan tangan") { a -> generic(FontFamily.Cursive, "cursive", a) },
        FontOption("mesin_tik", "Mesin tik (Mono)") { a -> generic(FontFamily.Monospace, "monospace", a) },
    )

    private val current = androidx.compose.runtime.mutableStateOf(options[0])

    /** Tambahan ketebalan huruf (bobot): 0 = ketebalan asli font (Default), negatif = lebih tipis, positif = lebih tebal. */
    private val weightAdj = androidx.compose.runtime.mutableIntStateOf(0)

    /** Batas slider "Ketebalan Font": ujung kiri = [MIN_ADJ] (Thin), ujung kanan = [MAX_ADJ] (Bold). */
    const val MIN_ADJ = -300
    const val MAX_ADJ = 300

    /** Posisi "Default" pada slider (sedikit di kiri tengah; jalur kiri lebih panjang agar tulisan tipis punya banyak pilihan). */
    const val DEFAULT_FRACTION = 0.4f

    val key: String get() = current.value.key
    val label: String get() = current.value.label
    val weight: Int get() = weightAdj.intValue
    val family: FontFamily get() = current.value.familyFor(weightAdj.intValue)

    /** Keluarga untuk teks balasan AI/judul: selalu mengikuti gaya pilihan pengguna (Default = gaya iOS/Inter, tanpa serif khusus). */
    val serifFamily: FontFamily get() = family

    fun setWeight(adj: Int) {
        weightAdj.intValue = adj.coerceIn(MIN_ADJ, MAX_ADJ)
    }

    /** 0..1: seberapa tipis ketebalan sekarang (0 = Default atau lebih tebal, 1 = paling tipis). Dipakai untuk menggelapkan warna teks. */
    val thinBoost: Float get() = if (weightAdj.intValue >= 0) 0f else (-weightAdj.intValue / -MIN_ADJ.toFloat()).coerceIn(0f, 1f)

    val isBold: Boolean get() = weightAdj.intValue > 0

    /** Posisi slider (0..1) untuk tambahan bobot [adj]; Default (0) jatuh di [DEFAULT_FRACTION]. */
    fun adjToFraction(adj: Int): Float {
        val a = adj.coerceIn(MIN_ADJ, MAX_ADJ)
        return if (a <= 0) (a - MIN_ADJ).toFloat() / -MIN_ADJ * DEFAULT_FRACTION
        else DEFAULT_FRACTION + a.toFloat() / MAX_ADJ * (1f - DEFAULT_FRACTION)
    }

    /** Kebalikan [adjToFraction]: dibulatkan ke kelipatan 10 dan menempel ke Default bila dekat. */
    fun fractionToAdj(f: Float): Int {
        val x = f.coerceIn(0f, 1f)
        if (kotlin.math.abs(x - DEFAULT_FRACTION) < 0.03f) return 0
        val raw = if (x <= DEFAULT_FRACTION) MIN_ADJ + x / DEFAULT_FRACTION * -MIN_ADJ
        else (x - DEFAULT_FRACTION) / (1f - DEFAULT_FRACTION) * MAX_ADJ
        return (Math.round(raw / 10f) * 10).coerceIn(MIN_ADJ, MAX_ADJ)
    }

    fun select(key: String) {
        current.value = options.firstOrNull { it.key == key } ?: options[0]
    }
}

/**
 * Ukuran huruf aplikasi (Pengaturan > Tampilan > Ukuran Font). Aplikasi TIDAK mengikuti ukuran font perangkat:
 * [com.mythos.app.ui.MythosTheme] menimpa skala font sistem dengan skala pilihan ini, jadi hanya pengaturan di dalam aplikasi yang berlaku.
 */
object FontScale {
    /** 5 langkah seperti pengaturan font sistem: Kecil, Default, Besar, Sangat Besar, Terbesar. */
    val scales = listOf(0.9f, 1.0f, 1.1f, 1.2f, 1.3f)
    const val DEFAULT_STEP = 1
    val labels: List<String> = listOf("Kecil", "Default", "Besar", "Sangat Besar", "Terbesar")

    private val current = androidx.compose.runtime.mutableIntStateOf(DEFAULT_STEP)

    val step: Int get() = current.intValue
    val scale: Float get() = scales[current.intValue]
    val label: String get() = labels[current.intValue]

    fun select(step: Int) {
        current.intValue = step.coerceIn(0, scales.lastIndex)
    }
}
