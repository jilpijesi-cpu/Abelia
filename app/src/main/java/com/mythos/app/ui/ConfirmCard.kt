package com.mythos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Satu tombol aksi pada kartu konfirmasi (disusun menumpuk, lebar penuh). */
class ConfirmAction(val label: String, val onClick: () -> Unit)

private val CardWhite = Color(0xFFFFFFFF)
private val ButtonBlue = Color(0xFFD3E3FD)
private val ButtonInk = Color(0xFF041E49)
private val PillBg = Color(0xFFF0F4F9)
private val HeaderInk = Color(0xFF444746)
private val BodyInk = Color(0xFF1F1F1F)

/**
 * Kartu konfirmasi bergaya dialog Google Play Protect:
 * kartu putih bersudut 28 dp, judul besar di tengah, baris info bulat berlatar abu kebiruan,
 * teks isi di tengah, lalu tombol biru muda selebar kartu yang disusun ke bawah dengan jarak 12 dp.
 *
 * [header] = judul kecil di atas (dengan ikon perisai), [pill] = baris info (mis. nama alat), [content] = isi tambahan.
 */
@Composable
fun MythosConfirmCard(
    title: String,
    onDismiss: () -> Unit,
    actions: List<ConfirmAction>,
    header: String? = "Mythos",
    pill: String? = null,
    message: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FixedScale {
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(CardWhite, shape)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (header != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Security, null, tint = HeaderInk, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(header, style = TextStyle(fontFamily = GmFont, fontWeight = FontWeight.Normal, fontSize = 17.sp, color = HeaderInk))
                }
                Spacer(Modifier.height(22.dp))
            }
            Text(
                title,
                textAlign = TextAlign.Center,
                style = TextStyle(fontFamily = GmFont, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 32.sp, color = BodyInk),
            )
            if (pill != null) {
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(PillBg).padding(horizontal = 24.dp, vertical = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(pill, style = TextStyle(fontFamily = GmFont, fontWeight = FontWeight.Normal, fontSize = 18.sp, color = BodyInk), maxLines = 2)
                }
            }
            if (message != null) {
                Spacer(Modifier.height(24.dp))
                Text(
                    message,
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontFamily = GmFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp, color = BodyInk),
                )
            }
            Column(Modifier.fillMaxWidth().padding(top = if (message != null || pill != null) 16.dp else 20.dp), content = content)
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.forEach { a ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ButtonBlue)
                            .clickable(onClickLabel = a.label, role = Role.Button, onClick = a.onClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(a.label, style = TextStyle(fontFamily = GmFont, fontWeight = FontWeight.Normal, fontSize = 17.sp, color = ButtonInk))
                    }
                }
            }
        }
        }
    }
}

/**
 * Kartu konfirmasi KECIL (PATCH36): satu baris isi, peringatan merah singkat bila perlu, "Detail" tersembunyi,
 * dan dua tombol berdampingan (Tolak | Izinkan). Tidak memenuhi layar.
 */
@Composable
fun MythosCompactConfirm(
    line: String,
    warning: String?,
    detail: String?,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    allowLabel: String = "Izinkan",
    denyLabel: String = "Tolak",
) {
    var showDetail by remember(line) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDeny, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FixedScale {
            val shape = RoundedCornerShape(20.dp)
            Column(
                Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(CardWhite, shape)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(line, maxLines = 3, style = TextStyle(fontFamily = GmFont, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp, color = BodyInk))
                if (warning != null) {
                    Text(warning, modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = GmFont, fontSize = 13.sp, color = Color(0xFFB3261E)))
                }
                if (detail != null) {
                    Text(
                        if (showDetail) "Sembunyikan" else "Detail",
                        modifier = Modifier.padding(top = 8.dp).clickable { showDetail = !showDetail },
                        style = TextStyle(fontFamily = GmFont, fontSize = 13.sp, color = Color(0xFF0B57D0)),
                    )
                    if (showDetail) {
                        Column(Modifier.padding(top = 6.dp).heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                            Text(detail, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = HeaderInk))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(PillBg)
                            .clickable(onClickLabel = denyLabel, role = Role.Button, onClick = onDeny),
                        contentAlignment = Alignment.Center,
                    ) { Text(denyLabel, style = TextStyle(fontFamily = GmFont, fontSize = 16.sp, color = BodyInk)) }
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(ButtonBlue)
                            .clickable(onClickLabel = allowLabel, role = Role.Button, onClick = onAllow),
                        contentAlignment = Alignment.Center,
                    ) { Text(allowLabel, style = TextStyle(fontFamily = GmFont, fontSize = 16.sp, color = ButtonInk)) }
                }
            }
        }
    }
}
