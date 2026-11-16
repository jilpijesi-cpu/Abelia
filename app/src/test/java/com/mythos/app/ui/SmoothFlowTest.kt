package com.mythos.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** patch18: alur live -> mengetik -> live berikutnya harus mengalir lembut. */
class SmoothFlowTest {
    private val chat get() = File("src/main/java/com/mythos/app/ui/ChatScreen.kt").readText()

    @Test fun fadeEkorDimatikanUntukTesDiagnostik() {
        assertEquals(0, TAIL_FADE_CHARS)
    }

    @Test fun tanpaFadeTidakMenambahkanRentangWarna() {
        val src = AnnotatedString("a".repeat(40))
        val faded = withTailFade(src, Color.Black, 0f)
        val lifted = withTailFade(src, Color.Black, 1f)
        assertTrue(faded.spanStyles.isEmpty())
        assertTrue(lifted.spanStyles.isEmpty())
    }

    @Test fun semuaBarisDaftarMemakaiAnimasiItemDanBukanSpacerEkor() {
        assertTrue(chat.contains("fadeInSpec = if (steps) null else FLOW_FADE_IN"))
        assertTrue(chat.split("Modifier.animateItem(").size - 1 == 2)   // blok + slot live
        assertTrue(chat.contains("item(key = TAIL_KEY) { Spacer(Modifier.height(tailDp)) }"))
    }

    @Test fun ekorDiangkatPelanBukanDilepasMendadak() {
        assertTrue(chat.contains("val tailLift by animateFloatAsState"))
        assertTrue(chat.contains("withTailFade(base, MythosColors.Ink, tailLift)"))
    }
}
