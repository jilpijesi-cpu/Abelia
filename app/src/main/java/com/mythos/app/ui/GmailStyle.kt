package com.mythos.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

// Gaya bersama semua kartu di aplikasi (draf email, berkas, perubahan, konfirmasi): palet, font, dan skala tetap bergaya Gmail.
// Kartu tidak memakai FontTheme/MythosSans, tidak mengikuti Ukuran Font aplikasi maupun ukuran font perangkat, dan tidak berubah oleh mode gelap.

internal val GmInk = Color(0xFF000000)
internal val GmGray = Color(0xFF444746)
internal val GmPaper = Color(0xFFFEFEFE)
internal val GmEdge = Color(0xFFE3E4E8)
internal val GmBar = Color(0xFFF4F5F9)
internal val GmChip = Color(0xFFF1F3F8)
internal val GmRule = Color(0xFFD0D3DA)
internal val GmRed = Color(0xFFDB4437)
internal val GmError = Color(0xFFD93025)
internal val GmBlue = Color(0xFF0B57D0)

/** Font tetap (Roboto pada Android), tidak mengikuti gaya/ketebalan font pilihan pengguna. */
internal val GmFont = FontFamily.SansSerif

internal fun gt(
    size: Float, line: Float, color: Color = GmInk, weight: FontWeight = FontWeight.Normal, align: TextAlign = TextAlign.Start,
) = TextStyle(
    fontFamily = GmFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    color = color, letterSpacing = 0.sp, textAlign = align,
)

/** Kunci skala font isi ke 1 (kepadatan layar tetap): teks kartu tidak membesar/mengecil oleh Ukuran Font di aplikasi maupun di perangkat. */
@Composable
internal fun FixedScale(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, 1f), content = content)
}
