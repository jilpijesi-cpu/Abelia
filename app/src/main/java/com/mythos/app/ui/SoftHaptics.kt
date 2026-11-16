package com.mythos.app.ui

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * patch22: getar ringan seperti Claude iOS, satu kali setiap teks live AI tampil atau berubah (tidak pernah saat teks jawaban mengetik).
 * Dibatasi jarak minimum antar getaran dan bisa dimatikan di Pengaturan > Tampilan. Tombol "Metode" memilih cara getar yang bekerja di HP ini.
 */
object HapticPrefs {
    var enabled by mutableStateOf(true)
    var method by mutableStateOf(0)
}

// Hanya membuang getar ganda dalam satu kedipan; ritme sebenarnya sudah dibatasi LABEL_HOLD_MS pada pergantian label.
internal const val SOFT_HAPTIC_LIVE_GAP_MS = 150L

/** [lastMs] < 0 berarti belum pernah bergetar. */
internal fun softHapticAllowed(lastMs: Long, nowMs: Long, gapMs: Long): Boolean = lastMs < 0 || nowMs - lastMs >= gapMs

private object SoftHapticClock {
    @Volatile var last = -1L
}

// patch21: 20 ms/90 terlalu pendek untuk motor rotor (butuh waktu berputar). 40 ms/150 masih ringan tapi terasa.
internal const val SOFT_VIBRATION_MS = 40L
internal const val SOFT_VIBRATION_AMPLITUDE = 150

internal val SOFT_METHODS = listOf("Otomatis", "Kuat", "Notifikasi", "Alarm", "Compose")
internal const val SOFT_METHOD_STRONG = 1
internal const val SOFT_METHOD_NOTIFICATION = 2
internal const val SOFT_METHOD_ALARM = 3
internal const val SOFT_METHOD_COMPOSE = 4

private const val METHOD_PREF = "soft_haptic_method"

private fun readMethod(context: Context): Int =
    context.applicationContext.getSharedPreferences("mythos_settings", Context.MODE_PRIVATE).getInt(METHOD_PREF, 0).coerceIn(0, SOFT_METHODS.lastIndex)

private fun writeMethod(context: Context, m: Int) {
    context.applicationContext.getSharedPreferences("mythos_settings", Context.MODE_PRIVATE).edit().putInt(METHOD_PREF, m).apply()
}

/**
 * Getar lewat Vibrator langsung. [method]: 0 Otomatis (atribut bawaan), 1 Kuat (80 ms, penuh), 2 Notifikasi, 3 Alarm
 * (atribut penggunaan berbeda untuk melewati penyaring sistem), 4 Compose (mengembalikan false agar pemanggil memakai haptik bawaan Compose).
 * Mengembalikan false kalau perangkat tidak punya getar, pemanggilan gagal, atau metode Compose dipilih.
 */
@Suppress("DEPRECATION")
internal fun softVibrate(context: Context, method: Int = HapticPrefs.method): Boolean {
    if (method == SOFT_METHOD_COMPOSE) return false
    val app = context.applicationContext ?: context
    val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (v == null || !v.hasVibrator()) return false
    return runCatching {
        val strong = method == SOFT_METHOD_STRONG
        val ms = if (strong) 80L else SOFT_VIBRATION_MS
        val amp = if (!v.hasAmplitudeControl()) VibrationEffect.DEFAULT_AMPLITUDE else if (strong) 255 else SOFT_VIBRATION_AMPLITUDE
        val effect = VibrationEffect.createOneShot(ms, amp)
        val usage = when (method) {
            SOFT_METHOD_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
            SOFT_METHOD_ALARM -> AudioAttributes.USAGE_ALARM
            else -> -1
        }
        if (usage >= 0) {
            v.vibrate(effect, AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        } else {
            v.vibrate(effect)
        }
        true
    }.getOrDefault(false)
}

/** Ringkasan kondisi HP untuk tombol "Coba" (ditampilkan sebagai Toast) agar penyebab getar tidak terasa bisa dibaca. */
internal fun softHapticReport(context: Context, method: Int, usedVibrator: Boolean): String {
    val app = context.applicationContext ?: context
    val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") (app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    val ringer = when ((app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> "dering"
        AudioManager.RINGER_MODE_VIBRATE -> "getar"
        AudioManager.RINGER_MODE_SILENT -> "SENYAP"
        else -> "?"
    }
    val save = if ((app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true) "ya" else "tidak"
    val dnd = when ((app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "mati"
        null -> "?"
        else -> "AKTIF"
    }
    return "Metode ${SOFT_METHODS[method.coerceIn(0, SOFT_METHODS.lastIndex)]} (${if (usedVibrator) "Vibrator" else "Compose"}) | motor: ${if (v?.hasVibrator() == true) "ada" else "TIDAK ADA"}" +
        " | amplitudo: ${if (v?.hasAmplitudeControl() == true) "ya" else "tidak"} | mode: $ringer | hemat daya: $save | Jangan Ganggu: $dnd"
}

/** Mengembalikan fungsi getar: panggil dengan jarak minimum (ms). Cadangan: getar bawaan Compose bila Vibrator tidak tersedia. */
@Composable
internal fun rememberSoftHaptic(): (Long) -> Unit {
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    remember(ctx) { HapticPrefs.method = readMethod(ctx); Unit }
    return remember(haptic, ctx) {
        { gapMs: Long ->
            val now = SystemClock.uptimeMillis()
            if (HapticPrefs.enabled && softHapticAllowed(SoftHapticClock.last, now, gapMs)) {
                SoftHapticClock.last = now
                if (!softVibrate(ctx)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }
}

/** Baris sakelar "Getar ringan" + pemilih metode + tombol "Coba" untuk Pengaturan > Tampilan. */
@Composable
internal fun HapticsRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    remember(ctx) { HapticPrefs.method = readMethod(ctx); Unit }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Getar ringan", style = TextStyle(fontFamily = MythosSans, fontSize = 16.sp, color = MythosColors.Ink))
                Text("Satu kali setiap teks live AI berubah", style = TextStyle(fontFamily = MythosSans, fontSize = 12.sp, color = MythosColors.Hint))
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val next = (HapticPrefs.method + 1) % SOFT_METHODS.size
                HapticPrefs.method = next
                writeMethod(ctx, next)
            }) { Text("Metode: ${SOFT_METHODS[HapticPrefs.method.coerceIn(0, SOFT_METHODS.lastIndex)]}", style = TextStyle(fontFamily = MythosSans, fontSize = 14.sp, color = MythosColors.Ink)) }
            // "Coba": getar seketika (tanpa batas jarak) dan menampilkan kondisi HP.
            TextButton(onClick = {
                val m = HapticPrefs.method
                val ok = softVibrate(ctx, m)
                if (!ok) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                Toast.makeText(ctx, softHapticReport(ctx, m, ok), Toast.LENGTH_LONG).show()
            }) { Text("Coba", style = TextStyle(fontFamily = MythosSans, fontSize = 14.sp, color = MythosColors.Hint)) }
        }
    }
}
