package com.mythos.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import com.mythos.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Jenis isi latar: warna polos, gradien vertikal, gambar bawaan (drawable), atau foto pilihan pengguna. */
enum class BgKind { SOLID, GRADIENT, IMAGE, CUSTOM }

/**
 * Satu pilihan latar belakang.
 * [top] dan [bottom] = warna tepi atas/bawah; dipakai bilah atas & area input di ChatScreen supaya menyatu dengan latar
 * (untuk gambar, nilainya adalah rata-rata tepi gambar).
 */
class BgOption(
    val key: String,
    val label: String,
    val kind: BgKind,
    val top: Color,
    val bottom: Color = top,
    val resId: Int = 0,
)

/**
 * Latar belakang aplikasi yang bisa diganti di Pengaturan > Tampilan > Latar belakang.
 * Dibaca dari state Compose, jadi semua layar ikut berubah seketika; pilihan disimpan oleh [com.mythos.app.SettingsStore].
 */
object AppBackground {
    const val KEY_DEFAULT = "default"
    const val KEY_CUSTOM = "custom"

    /** Sisi terpanjang foto pengguna setelah diperkecil: cukup untuk layar HP 1440p+ tanpa terlihat pecah. */
    const val MAX_SIDE = 2880

    val options: List<BgOption> = listOf(
        BgOption(KEY_DEFAULT, "Bawaan", BgKind.SOLID, Color(0xFFF9F9F6)),
        BgOption("langit_biru", "Langit biru", BgKind.IMAGE, Color(0xFFFEFEFE), Color(0xFFADD8FD), R.drawable.bg_langit_biru),
        BgOption("putih", "Putih", BgKind.SOLID, Color(0xFFFFFFFF)),
        BgOption("senja", "Senja", BgKind.GRADIENT, Color(0xFFFFFBF7), Color(0xFFFFD9C7)),
        BgOption("mint", "Mint", BgKind.GRADIENT, Color(0xFFF9FFFC), Color(0xFFBFEBDD)),
        BgOption("lavender", "Lavender", BgKind.GRADIENT, Color(0xFFFCFAFF), Color(0xFFD9CCF7)),
        BgOption("mawar", "Mawar", BgKind.GRADIENT, Color(0xFFFFFAFC), Color(0xFFFAC9DD)),
        BgOption("kuning_lembut", "Kuning lembut", BgKind.SOLID, Color(0xFFFFF8E1)),
        BgOption("hijau_lembut", "Hijau lembut", BgKind.SOLID, Color(0xFFEEF6EA)),
        BgOption("abu_lembut", "Abu lembut", BgKind.SOLID, Color(0xFFEDEDEB)),
    )

    /** Opsi foto pengguna; warna tepi diperbarui saat gambar dimuat. */
    private val customOption = mutableStateOf(BgOption(KEY_CUSTOM, "Foto saya", BgKind.CUSTOM, Color(0xFFF9F9F6)))
    private val current = mutableStateOf(options[0])

    /** Bitmap foto pengguna (null bila belum ada). */
    var customBitmap by mutableStateOf<Bitmap?>(null)
        private set

    val key: String get() = current.value.key
    val option: BgOption get() = current.value
    val custom: BgOption get() = customOption.value
    val hasCustom: Boolean get() = customBitmap != null

    /** Warna tepi atas dan bawah latar yang sedang dipakai. */
    val topColor: Color get() = current.value.top
    val bottomColor: Color get() = current.value.bottom

    /** Pilih latar. Kunci tak dikenal → bawaan; "custom" tanpa foto → bawaan. */
    fun select(key: String) {
        current.value = when {
            key == KEY_CUSTOM && customBitmap != null -> customOption.value
            else -> options.firstOrNull { it.key == key } ?: options[0]
        }
    }

    private fun customFile(ctx: Context) = File(ctx.filesDir, "bg_custom.jpg")

    /** Muat foto pengguna dari penyimpanan aplikasi (jika ada). Aman dipanggil berulang. */
    fun loadCustom(ctx: Context) {
        val f = customFile(ctx)
        val bmp = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() else null
        applyCustom(bmp)
    }

    private fun applyCustom(bmp: Bitmap?) {
        customBitmap = bmp
        customOption.value = if (bmp == null) {
            BgOption(KEY_CUSTOM, "Foto saya", BgKind.CUSTOM, Color(0xFFF9F9F6))
        } else {
            BgOption(KEY_CUSTOM, "Foto saya", BgKind.CUSTOM, edgeColor(bmp, top = true), edgeColor(bmp, top = false))
        }
        if (current.value.key == KEY_CUSTOM) current.value = if (bmp == null) options[0] else customOption.value
    }

    /** Rata-rata warna 6% baris paling atas/bawah. */
    internal fun edgeColor(bmp: Bitmap, top: Boolean): Color {
        val h = bmp.height
        val band = maxOf(1, (h * 0.06f).toInt())
        val y0 = if (top) 0 else h - band
        val slice = Bitmap.createBitmap(bmp, 0, y0, bmp.width, band)
        val one = Bitmap.createScaledBitmap(slice, 1, 1, true)
        val px = one.getPixel(0, 0)
        return Color(0xFF000000.toInt() or (px and 0x00FFFFFF))
    }

    /**
     * Salin foto pilihan pengguna ke penyimpanan aplikasi (diperkecil maks. 1600 px, rotasi EXIF diterapkan).
     * Mengembalikan true bila berhasil. Aman dipanggil dari coroutine mana pun.
     */
    suspend fun importCustom(ctx: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@runCatching false
            val rotation = runCatching {
                resolver.openInputStream(uri)?.use {
                    when (android.media.ExifInterface(it).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
                    )) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            }.getOrDefault(0f)
            if (rotation != 0f) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
            }
            val big = maxOf(bmp.width, bmp.height)
            if (big > MAX_SIDE) {
                val k = MAX_SIDE.toFloat() / big
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
            }
            customFile(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            withContext(Dispatchers.Main) { applyCustom(bmp) }
            true
        }.getOrDefault(false)
    }
}

/** Gradien vertikal dengan dither: memecah pita warna (banding) yang tampak pada gradien 8-bit di layar OLED/LCD modern. */
internal fun Modifier.ditheredGradient(top: Color, bottom: Color): Modifier = drawBehind {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            isAntiAlias = false
            shader = LinearGradientShader(Offset.Zero, Offset(0f, size.height), listOf(top, bottom))
        }
        canvas.drawRect(0f, 0f, size.width, size.height, paint)
    }
}

/** Painter gambar dengan filter kualitas TERTINGGI (bikubik + mipmap) agar gambar tetap tajam saat diskalakan/dipotong ke ukuran layar. */
private fun sharpPainter(img: ImageBitmap) = BitmapPainter(img, filterQuality = FilterQuality.High)

/** Gambar latar sesuai [opt]; gambar diisi penuh (crop tengah) tanpa terdistorsi, dengan kualitas filter tertinggi. */
internal fun Modifier.backgroundFor(opt: BgOption): Modifier = composed {
    when (opt.kind) {
        BgKind.SOLID -> this.background(opt.top)
        BgKind.GRADIENT -> this.ditheredGradient(opt.top, opt.bottom)
        BgKind.IMAGE -> {
            val img = ImageBitmap.imageResource(opt.resId)
            val painter = remember(img) { sharpPainter(img) }
            this.background(opt.top).clipToBounds().paint(painter, contentScale = ContentScale.Crop)
        }
        BgKind.CUSTOM -> {
            val bmp = AppBackground.customBitmap
            if (bmp == null) this.background(opt.top)
            else {
                val painter = remember(bmp) { sharpPainter(bmp.asImageBitmap()) }
                this.background(opt.top).clipToBounds().paint(painter, contentScale = ContentScale.Crop)
            }
        }
    }
}

/** Pengganti `.background(MythosColors.Background)` untuk layar penuh: mengikuti pilihan latar pengguna. */
fun Modifier.appBackground(): Modifier = backgroundFor(AppBackground.option)
