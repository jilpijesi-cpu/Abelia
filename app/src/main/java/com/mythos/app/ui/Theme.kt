package com.mythos.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color

@Composable
fun MythosTheme(content: @Composable () -> Unit) {
    // Skala font perangkat diabaikan: hanya pilihan di Pengaturan > Tampilan > Ukuran Font yang berlaku (dibaca dari state, jadi seketika).
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, FontScale.scale)) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = MythosColors.Ink,
            onPrimary = Color.White,
            background = MythosColors.Background,
            onBackground = MythosColors.Ink,
            surface = MythosColors.Card,
            onSurface = MythosColors.Ink,
            surfaceVariant = Color(0xFFF1EFEA),
            onSurfaceVariant = MythosColors.Hint,
            outline = MythosColors.Border,
            error = Color(0xFFB3261E),
        ),
        // Semua gaya teks Material memakai satu keluarga font pilihan; teks tanpa fontFamily juga mewarisinya lewat ProvideTextStyle,
        // jadi tidak ada lagi teks yang jatuh ke font sistem dan "bercampur".
        typography = MythosType.materialTypography(),
        content = { ProvideTextStyle(TextStyle(fontFamily = FontTheme.family), content) }
    )
    }
}
