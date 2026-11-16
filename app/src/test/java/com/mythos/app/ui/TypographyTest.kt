package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Sistem tipografi terpusat (Typography.kt): angka, jarak antarblok, kolom daftar, kutipan, dan larangan gaya liar. */
class TypographyTest {
    private fun src(name: String) = File("src/main/java/com/mythos/app/ui/$name").readText()

    @Test fun titikAwalSesuaiPermintaan() {
        assertEquals(16f, TypeScale.BODY, 0f)
        assertEquals(1.6f, TypeScale.BODY_LINE, 0f)
        assertEquals(24f, TypeScale.headingSize(1), 0f)
        assertTrue("subjudul 18-20", TypeScale.headingSize(2) in 18f..20f && TypeScale.headingSize(3) in 18f..20f)
        assertEquals(TypeScale.BODY, TypeScale.headingSize(5), 0f)
        assertTrue("h1 > h2 > h3 > isi", TypeScale.headingSize(1) > TypeScale.headingSize(2) && TypeScale.headingSize(2) > TypeScale.headingSize(3) && TypeScale.headingSize(3) > TypeScale.BODY)
    }

    @Test fun jarakParagrafDalamRentang14Sampai16() {
        assertTrue(TypeScale.PARAGRAPH_GAP in 14f..16f)
        assertTrue(TypeScale.BLOCK_GAP in 14f..16f)
    }

    @Test fun jarakAntarblok() {
        val para = ProseBlock.Para("a")
        val item = ProseBlock.Item("1.", "b", 0)
        val nested = ProseBlock.Item("•", "c", 1)
        val table = ProseBlock.Table(listOf(listOf("x")))
        val quote = ProseBlock.Quote("q")
        assertEquals(0f, TypeScale.gapDp(null, para), 0f)
        assertEquals(TypeScale.LIST_ENTRY_GAP, TypeScale.gapDp(para, item), 0f)
        assertEquals(TypeScale.LIST_ITEM_GAP, TypeScale.gapDp(item, item), 0f)
        assertEquals(TypeScale.LIST_NESTED_GAP, TypeScale.gapDp(item, nested), 0f)
        assertEquals(TypeScale.PARAGRAPH_GAP, TypeScale.gapDp(item, para), 0f)
        assertEquals(TypeScale.BLOCK_GAP, TypeScale.gapDp(para, table), 0f)
        assertEquals(TypeScale.BLOCK_GAP, TypeScale.gapDp(quote, para), 0f)
        assertEquals(TypeScale.HEADING_AFTER, TypeScale.gapDp(ProseBlock.Heading(1, "j"), ProseBlock.Heading(2, "k")), 0f)
        assertTrue(TypeScale.gapDp(para, ProseBlock.Heading(1, "j")) > TypeScale.gapDp(para, ProseBlock.Heading(2, "j")))
    }

    @Test fun kolomPenandaDaftarLurusDariButir9Ke10() {
        val blocks = splitProse("9. sembilan\n10. sepuluh\n- titik\n\nParagraf")
        val cols = TypeScale.markerColumnsDp(blocks)
        assertEquals(blocks.size, cols.size)
        assertEquals("nomor satu daftar memakai lebar yang sama", cols[0], cols[1], 0f)
        assertEquals(TypeScale.numberedColumnDp(3), cols[0], 0f)
        assertEquals(TypeScale.BULLET_COL, cols[2], 0f)
        assertEquals(0f, cols[3], 0f)
        assertEquals("tanpa nomor 2 digit kolom lebih sempit", TypeScale.numberedColumnDp(2), TypeScale.markerColumnsDp(splitProse("1. a\n2. b"))[0], 0f)
    }

    @Test fun kutipanMenjadiBlokDanBarisBerurutanDigabung() {
        assertEquals(listOf(ProseBlock.Quote("satu\ndua")), splitProse("> satu\n> dua"))
        assertEquals(
            listOf(ProseBlock.Para("Awal"), ProseBlock.Quote("kutip"), ProseBlock.Para("Akhir")),
            splitProse("Awal\n> kutip\nAkhir")
        )
        assertEquals("baris kosong memisahkan dua kutipan", 2, splitProse("> a\n\n> b").filterIsInstance<ProseBlock.Quote>().size)
        assertEquals("tanda > tunggal bukan kutipan", listOf(ProseBlock.Para(">")), splitProse(">"))
    }

    @Test fun cleanForDisplayMempertahankanKutipanHanyaBilaDiminta() {
        assertEquals("kutipan", cleanForDisplay("> kutipan"))
        assertEquals("> kutipan", cleanForDisplay("> kutipan", keepQuotes = true))
        assertEquals("tanda > yang belum diikuti isi tidak tampil", "Halo", cleanForDisplay("Halo\n>", keepQuotes = true).trimEnd())
        assertFalse(plainText("> kutipan").startsWith(">"))
    }

    @Test fun temaMemakaiTypographyTerpusatDanTanpaJustify() {
        assertTrue(src("Theme.kt").contains("typography = MythosType.materialTypography()"))
        assertFalse("bawaan Material (letter-spacing 0,5sp) tidak boleh dipakai lagi", src("Theme.kt").contains("MaterialTheme.typography"))
        val dir = File("src/main/java/com/mythos/app")
        val all = dir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(all.isNotEmpty())
        for (f in all) assertFalse("${f.name} memakai TextAlign.Justify", f.readText().contains("TextAlign.Justify"))
    }

    @Test fun gayaPercakapanTidakMenulisUkuranSendiri() {
        val design = src("Design.kt")
        assertTrue(design.contains("internal fun bodyStyle(color: Color = MythosColors.Ink) = MythosType.body(color)"))
        assertTrue(design.contains("internal fun inputStyle(color: Color) = MythosType.input(color)"))
        val chat = src("ChatScreen.kt")
        assertFalse("gelembung pengguna harus memakai bodyStyle() apa adanya", chat.contains("bodyStyle().copy(fontSize"))
        val blocks = src("CopyBlocks.kt")
        assertTrue(blocks.contains("MythosType.heading(style, blk.level)"))
        assertTrue(blocks.contains("TypeScale.gapDp("))
        assertFalse("jarak blok tidak boleh ditulis ulang di renderer", Regex("""val top = if \(bi == 0\)""").containsMatchIn(blocks))
    }

    @Test fun skalaTypographyMaterialBerSpasiNol() {
        val t = src("Typography.kt")
        assertTrue(t.contains("letterSpacing = 0.sp"))
        assertTrue(t.contains("TextAlign.Start"))
        assertTrue(t.contains("Hyphens.None"))
        assertTrue(t.contains("LineBreak.Simple"))
        assertTrue(t.contains("textMotion = TextMotion.Animated"))
    }

    @Test fun teksSelesaiMemakaiParagraphDanTeksMengalirTetapSimple() {
        val chat = src("ChatScreen.kt")
        assertTrue(chat.contains("if (streaming) LineBreak.Simple else LineBreak.Paragraph"))
    }

    @Test fun judulSesiDiTengahBilahAtasDenganFontTetap() {
        val chat = src("ChatScreen.kt")
        assertTrue(chat.contains("Modifier.align(Alignment.Center).padding(horizontal = 100.dp)"))
        assertTrue(chat.contains("fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp"))
        assertFalse("judul tidak lagi di kiri", chat.contains("Modifier.weight(1f).padding(start = ChatMargin, end = 4.dp)"))
    }
}
