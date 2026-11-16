package com.mythos.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Baris "Latar belakang" di Pengaturan > Tampilan. Mengetuknya membuka dialog berisi pratinjau semua latar
 * (bawaan, gambar, gradien, warna polos) plus pilihan foto dari galeri. Berlaku seketika dan tersimpan.
 */
@Composable
internal fun BackgroundRow(selectedKey: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = if (selectedKey == AppBackground.KEY_CUSTOM && AppBackground.hasCustom) AppBackground.custom
    else AppBackground.options.firstOrNull { it.key == selectedKey } ?: AppBackground.options[0]

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Ubah latar belakang", role = Role.Button) { open = true }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Latar belakang",
            style = TextStyle(fontFamily = MythosSans, fontSize = 16.sp, color = MythosColors.Ink),
            modifier = Modifier.weight(1f)
        )
        BackgroundThumb(cur, Modifier.width(18.dp).height(28.dp), RoundedCornerShape(5.dp))
        Spacer(Modifier.width(8.dp))
        Text(cur.label, style = TextStyle(fontFamily = MythosSans, fontSize = 15.sp, color = MythosColors.Hint))
    }

    if (open) BackgroundDialog(selectedKey, onSelect) { open = false }
}

@Composable
private fun BackgroundThumb(opt: BgOption, modifier: Modifier, shape: RoundedCornerShape) {
    Box(
        modifier
            .clip(shape)
            .backgroundFor(opt)
            .border(1.dp, MythosColors.Border, shape)
    )
}

@Composable
private fun BackgroundDialog(selectedKey: String, onSelect: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = AppBackground.importCustom(ctx, uri)
            failed = !ok
            if (ok) onSelect(AppBackground.KEY_CUSTOM)
        }
    }

    // Daftar tampilan: semua pilihan bawaan, lalu "Foto saya" (hanya bila sudah ada fotonya).
    val shown = AppBackground.options + if (AppBackground.hasCustom) listOf(AppBackground.custom) else emptyList()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Latar belakang") },
        text = {
            Column {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                ) {
                    items(shown, key = { it.key }) { o ->
                        val on = o.key == selectedKey
                        Column(
                            Modifier.clickable(onClickLabel = "Pilih ${o.label}", role = Role.Button) {
                                onSelect(o.key)
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.62f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .backgroundFor(o)
                                    .border(
                                        if (on) 2.dp else 1.dp,
                                        if (on) MythosColors.Accent else MythosColors.Border,
                                        RoundedCornerShape(12.dp)
                                    ),
                                contentAlignment = Alignment.BottomEnd,
                            ) {
                                if (on) Box(
                                    Modifier.padding(6.dp).size(22.dp).clip(CircleShape).background(MythosColors.Accent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.Done, contentDescription = "Dipilih", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                o.label,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                style = TextStyle(fontFamily = MythosSans, fontSize = 12.sp, color = MythosColors.Hint)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClickLabel = "Pilih foto dari galeri", role = Role.Button) {
                            failed = false
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = MythosColors.Accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (AppBackground.hasCustom) "Ganti foto dari galeri" else "Pilih foto dari galeri",
                        style = TextStyle(fontFamily = MythosSans, fontSize = 15.sp, color = MythosColors.Ink)
                    )
                }
                if (failed) Text(
                    "Foto tidak bisa dibuka. Coba foto lain.",
                    style = TextStyle(fontFamily = MythosSans, fontSize = 12.sp, color = MythosColors.Error)
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Selesai") } },
    )
}
