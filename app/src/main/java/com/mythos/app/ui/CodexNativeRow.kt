package com.mythos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythos.app.SettingsStore

/** Pengaturan > Model > Codex CLI: sakelar akses penuh Codex ke Termux (bawaan hidup). */
@Composable
internal fun CodexNativeRow() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(runCatching { SettingsStore(ctx).loadCodexNative() }.getOrDefault(true)) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Akses penuh Termux", style = TextStyle(fontFamily = MythosSans, fontSize = 16.sp, color = MythosColors.Ink))
                Text(
                    "Codex menjalankan perintah di Termux tanpa sandbox dan tanpa persetujuan. Hanya Codex yang punya jalur ke Termux.",
                    style = TextStyle(fontFamily = MythosSans, fontSize = 12.sp, color = MythosColors.Hint)
                )
            }
            Switch(checked = on, onCheckedChange = { want ->
                on = want
                runCatching { SettingsStore(ctx).saveCodexNative(want) }
            })
        }
    }
}
