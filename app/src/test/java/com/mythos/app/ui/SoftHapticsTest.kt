package com.mythos.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** patch19: getar ringan hanya di dua titik, dibatasi jarak, dan bisa dimatikan. */
class SoftHapticsTest {
    private fun src(p: String) = File("src/main/java/com/mythos/app/$p").readText()

    @Test fun getaranPertamaSelaluBoleh() = assertTrue(softHapticAllowed(-1L, 0L, 900L))

    @Test fun getaranBerikutnyaMenungguJarakMinimum() {
        assertFalse(softHapticAllowed(1000L, 1100L, 150L))
        assertTrue(softHapticAllowed(1000L, 1150L, 150L))
    }

    @Test fun jarakLiveHanyaMembuangGetarGandaBukanPerubahanLabel() = assertTrue(SOFT_HAPTIC_LIVE_GAP_MS <= 200L)

    @Test fun bawaanHidupDanDipatuhi() {
        assertTrue(HapticPrefs.enabled)
        assertTrue(src("Settings.kt").contains("prefs.getBoolean(\"soft_haptics\", true)"))
        assertTrue(src("ui/SoftHaptics.kt").contains("HapticPrefs.enabled && softHapticAllowed("))
    }

    @Test fun hanyaSatuPemicuYaituPerubahanTeksLive() {
        val chat = src("ui/ChatScreen.kt")
        assertTrue(chat.contains("LaunchedEffect(live, label) { if (live) liveTick(SOFT_HAPTIC_LIVE_GAP_MS) }"))
        assertFalse(chat.contains("softTick"))          // tidak ada getar saat teks jawaban mengetik
        assertTrue(Regex("liveTick\\(").findAll(chat).count() == 1)
    }

    @Test fun memakaiVibratorLangsungDanIzinVibrateAda() {
        assertTrue(src("ui/SoftHaptics.kt").contains("VibrationEffect.createOneShot("))
        assertTrue(src("ui/SoftHaptics.kt").contains("if (!softVibrate(ctx)) haptic.performHapticFeedback"))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android.permission.VIBRATE"))
    }

    @Test fun adaSakelarDiPengaturanTampilan() {
        assertTrue(src("ui/SettingsScreen.kt").contains("HapticsRow(HapticPrefs.enabled, onHapticsChange)"))
        val main = src("MainActivity.kt")
        assertTrue(main.contains("SettingsStore(ctx).saveHaptics(on)"))
        // MythosApp adalah fungsi Composable tingkat atas, bukan anggota MainActivity: "this@MainActivity" tidak dikenal di sana (galat kompilasi).
        assertFalse(main.contains("this@MainActivity"))
    }
}
