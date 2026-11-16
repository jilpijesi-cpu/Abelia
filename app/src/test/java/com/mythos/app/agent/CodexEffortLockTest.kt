package com.mythos.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** PATCH34: upaya berpikir Codex dikunci ke "medium" (Seimbang); tidak ada lagi pengaturan di UI. */
class CodexEffortLockTest {
    @Test fun upayaDikunciKeMediumDanBarisPengaturanDihapus() {
        val settings = File("src/main/java/com/mythos/app/Settings.kt").readText()
        val screen = File("src/main/java/com/mythos/app/ui/SettingsScreen.kt").readText()
        val row = File("src/main/java/com/mythos/app/ui/CodexNativeRow.kt").readText()
        assertTrue(settings.contains("fun loadCodexEffort(): String = \"medium\""))
        assertFalse(settings.contains("saveCodexEffort"))
        assertFalse(screen.contains("CodexEffortRow()"))
        assertFalse(row.contains("CodexEffortRow"))
    }
}
