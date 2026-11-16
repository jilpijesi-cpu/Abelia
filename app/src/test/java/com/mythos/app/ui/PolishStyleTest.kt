package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** patch10: penghalusan tampilan (jarak baris balasan, jarak paragraf, umpan balik tekan tombol). */
class PolishStyleTest {
    private val design get() = File("src/main/java/com/mythos/app/ui/Design.kt").readText()

    @Test fun balasanAiMemakaiJarakBarisLonggar() {
        assertTrue("rasio jarak baris minimal 1,5 (didapat ${TypeScale.BODY_LINE})", TypeScale.BODY_LINE >= 1.5f)
        assertTrue(design.contains("MythosType.body(color).copy(fontFamily = MythosSerif)"))
    }

    @Test fun jarakParagrafDanJudulDilonggarkan() {
        val para = ProseBlock.Para("a")
        val item = ProseBlock.Item("•", "b", 0)
        val head = ProseBlock.Heading(2, "c")
        assertTrue("paragraf ke paragraf 14-16 dp", TypeScale.gapDp(para, para) in 14f..16f)
        assertTrue("butir lebih rapat dari paragraf", TypeScale.gapDp(item, item) < TypeScale.gapDp(para, para))
        assertTrue("judul menjauh dari isi di atasnya", TypeScale.gapDp(para, head) > TypeScale.gapDp(para, para))
        assertTrue("judul menempel ke isinya", TypeScale.gapDp(head, para) < TypeScale.gapDp(para, para))
    }

    @Test fun tombolBulatMemberiUmpanBalikTekan() {
        assertTrue(design.contains("fun Modifier.pressScale("))
        assertEquals("GlyphButton dan CircleIconButton", 2, Regex("""\.pressScale\(source\)""").findAll(design).count())
        assertEquals(2, Regex("""HapticFeedbackType\.TextHandleMove""").findAll(design).count())
    }
}

/** patch11: transisi antarlayar. */
class ScreenTransitionTest {
    private val main get() = File("src/main/java/com/mythos/app/MainActivity.kt").readText()

    @Test fun pindahLayarMemakaiAnimatedContent() {
        assertTrue(main.contains("AnimatedContent("))
        assertTrue(main.contains("contentKey = { if (it == Screen.CHAT) Screen.HOME else it }"))
    }
}
