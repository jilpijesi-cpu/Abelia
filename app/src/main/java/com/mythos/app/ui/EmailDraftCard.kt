package com.mythos.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythos.app.R
import kotlinx.coroutines.delay

/** Teks seluruh draf untuk tombol salin. */
internal fun emailDraftText(d: EmailDraftItem): String = buildString {
    append("Kepada: ").append(d.to.joinToString())
    if (d.cc.isNotEmpty()) append("\nCc: ").append(d.cc.joinToString())
    append("\nSubjek: ").append(d.subject).append("\n\n").append(d.body)
}

private val EMAIL_ADDR = Regex("""^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$""")

/** Pecah isi kolom Kepada/Cc (dipisah koma, titik koma, spasi, atau baris baru) menjadi daftar alamat unik. */
internal fun parseRecipients(text: String): List<String> =
    text.split(',', ';', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Alamat yang bentuknya tidak valid; kosong = semua valid. */
internal fun invalidRecipients(list: List<String>): List<String> = list.filterNot { EMAIL_ADDR.matches(it) }

/** Pesan galat untuk hasil edit, atau null bila boleh disimpan. */
internal fun emailEditError(to: List<String>, cc: List<String>, subject: String, body: String): String? = when {
    to.isEmpty() -> "Kolom Kepada tidak boleh kosong."
    invalidRecipients(to).isNotEmpty() -> "Alamat Kepada tidak valid: " + invalidRecipients(to).first()
    invalidRecipients(cc).isNotEmpty() -> "Alamat Cc tidak valid: " + invalidRecipients(cc).first()
    subject.isBlank() -> "Subjek tidak boleh kosong."
    body.isBlank() -> "Isi email tidak boleh kosong."
    else -> null
}

/** Potongan isi email: paragraf biasa, atau butir daftar bernomor/berpoin (dengan indentasi menggantung seperti di Gmail). */
internal sealed class EmailBodyBlock {
    data class Para(val text: String) : EmailBodyBlock()
    data class Item(val marker: String, val text: String) : EmailBodyBlock()
}

private val LIST_ITEM = Regex("""^\s{0,6}(\d{1,3}[.)]|[-*•])\s+(.+)$""")

/** Pecah isi email menjadi paragraf (dipisah baris kosong) dan butir daftar ("1. ...", "- ..."); baris lanjutan menyatu ke butir di atasnya. */
internal fun parseEmailBody(body: String): List<EmailBodyBlock> {
    val out = ArrayList<EmailBodyBlock>()
    val para = ArrayList<String>()
    var marker: String? = null
    var itemText = StringBuilder()
    fun flushPara() { if (para.isNotEmpty()) { out += EmailBodyBlock.Para(para.joinToString("\n")); para.clear() } }
    fun flushItem() { marker?.let { out += EmailBodyBlock.Item(it, itemText.toString()) }; marker = null; itemText = StringBuilder() }
    for (raw in body.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        val line = raw.trimEnd()
        if (line.isBlank()) { flushPara(); flushItem(); continue }
        val m = LIST_ITEM.matchEntire(line)
        when {
            m != null -> {
                flushPara(); flushItem()
                val mk = m.groupValues[1]
                marker = if (mk[0].isDigit()) mk else "•"
                itemText.append(m.groupValues[2].trim())
            }
            marker != null -> itemText.append(' ').append(line.trim())
            else -> para += line
        }
    }
    flushPara(); flushItem()
    return out
}

/** Nama pengirim/penerima gaya Gmail dari alamat pertama: "lapor@flip.id" -> "Lapor Flip". */
internal fun draftSenderName(to: List<String>): String {
    val a = to.firstOrNull() ?: return "Draf email"
    val local = a.substringBefore('@')
    val domain = a.substringAfter('@', "").substringBefore('.')
    val words = (local.split('.', '_', '-', '+') + domain).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    return words.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.ifBlank { a }
}

/** Baris kedua kepala kartu: "kepada lapor@flip.id" atau "kepada lapor@flip.id +2". */
internal fun draftToSummary(to: List<String>): String = when {
    to.isEmpty() -> "kepada -"
    to.size == 1 -> "kepada " + to[0]
    else -> "kepada " + to[0] + " +" + (to.size - 1)
}

// ---------------------------------------------------------------------------------------------------------------------
// Tampilan kartu draf = salinan 1:1 tampilan surat Gmail (tangkapan layar acuan, lebar 974 px).
//
// Semua ukuran di bawah ditulis dalam "satuan desain" = piksel tangkapan layar acuan. Kartu memasang LocalDensity sendiri
// (density = lebar kartu / 974, fontScale = 1) sehingga 1.dp = 1 satuan desain. Akibatnya tampilan kartu:
//   - tidak terpengaruh ukuran font perangkat, ukuran tampilan (display size), maupun Pengaturan > Ukuran Font / Gaya font /
//     Ketebalan Font di dalam aplikasi, dan tidak berubah oleh mode gelap;
//   - selalu proporsional sama persis dengan acuan di lebar layar apa pun (jarak, ukuran teks, ikon, pemenggalan baris).
// Hanya warna, tata letak, dan font di file ini yang menentukan tampilannya.
// ---------------------------------------------------------------------------------------------------------------------

/** Lebar acuan (px tangkapan layar). Mengubah angka ini memperbesar/memperkecil seluruh kartu secara seragam. */
internal const val GMAIL_DESIGN_WIDTH = 974f

/**
 * Kartu draf email di chat, tata letak persis surat Gmail: logo Gmail 4 warna, nama + "Draf", baris "kepada ..." dengan panah
 * perluasan, ikon pensil (ubah) / balas (kirim) / titik tiga (menu), isi surat apa adanya (paragraf, daftar bernomor), dan
 * bilah lampiran di bawah. Pensil mengubah draf langsung di kartu; panah balas mengirim lewat alur PATCH7 (Gmail API bila
 * terhubung, bila tidak membuka aplikasi email); panah di samping "kepada" menampilkan Kepada/Cc/Subjek lengkap.
 */
@Composable
internal fun EmailDraftCard(
    item: EmailDraftItem,
    onOpenPath: (String, Boolean) -> Unit,
    onEdit: (to: List<String>, cc: List<String>, subject: String, body: String) -> Unit = { _, _, _, _ -> },
    onSend: () -> Unit,
) {
    val outer = LocalDensity.current
    val popupDensity = Density(outer.density, 1f) // menu popup memakai ukuran tetap, bukan satuan desain
    val clipboard = LocalClipboardManager.current
    var copied by remember(item.id) { mutableStateOf(false) }
    var menu by remember(item.id) { mutableStateOf(false) }
    var expanded by remember(item.id) { mutableStateOf(false) }
    var editing by remember(item.id) { mutableStateOf(false) }
    var editError by remember(item.id) { mutableStateOf("") }
    // Nilai awal tiap kali mode ubah dibuka, diambil dari draf yang tersimpan.
    var toText by remember(item.id, editing) { mutableStateOf(item.to.joinToString(", ")) }
    var ccText by remember(item.id, editing) { mutableStateOf(item.cc.joinToString(", ")) }
    var subjectText by remember(item.id, editing) { mutableStateOf(item.subject) }
    var bodyText by remember(item.id, editing) { mutableStateOf(item.body) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val busy = item.sending || item.sent
    val canEdit = !busy
    LaunchedEffect(busy) { if (busy) editing = false }
    val blocks = remember(item.body) { parseEmailBody(item.body) }

    fun save() {
        val to = parseRecipients(toText)
        val cc = parseRecipients(ccText)
        val err = emailEditError(to, cc, subjectText, bodyText)
        if (err != null) { editError = err; return }
        editError = ""
        if (to != item.to || cc != item.cc || subjectText.trim() != item.subject || bodyText.trimEnd() != item.body) {
            onEdit(to, cc, subjectText, bodyText)
        }
        editing = false
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        val widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else outer.density * 360f
        CompositionLocalProvider(LocalDensity provides Density(widthPx / GMAIL_DESIGN_WIDTH, 1f)) {
            val shape = RoundedCornerShape(52.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(GmPaper)
                    .border(2.dp, GmEdge, shape)
                    .padding(bottom = 28.dp)
            ) {
                // ---- Kepala: logo, nama + "Draf", "kepada ...", tiga ikon aksi ----
                Row(Modifier.fillMaxWidth().padding(start = 39.dp, top = 27.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_gmail_color), contentDescription = null, modifier = Modifier.size(width = 96.dp, height = 72.dp))
                    Spacer(Modifier.width(53.dp))
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .clickable(onClickLabel = if (expanded) "Sembunyikan detail penerima" else "Tampilkan detail penerima", role = Role.Button) { expanded = !expanded }
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            BasicText(draftSenderName(item.to), style = gt(33f, 40f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(14.dp))
                            BasicText(if (item.sent) "Terkirim" else "Draf", style = gt(27f, 36f, GmGray), maxLines = 1)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText(draftToSummary(item.to), style = gt(31f, 38f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = GmInk, modifier = Modifier.size(40.dp).rotate(if (expanded) 180f else 0f))
                        }
                    }
                    // Tiga aksi: pusat ikon di x = 684, 800, 916 (acuan), kotak sentuh 116 x 96 sampai tepi kanan kartu.
                    HeaderAction(
                        label = if (editing) "Simpan perubahan draf" else "Ubah draf email", enabled = canEdit,
                        onClick = { if (editing) save() else { editError = ""; editing = true } },
                    ) {
                        Icon(
                            if (editing) Icons.Outlined.Done else Icons.Outlined.Edit,
                            contentDescription = if (editing) "Simpan" else "Ubah",
                            tint = GmInk, modifier = Modifier.size(54.dp).alpha(if (canEdit) 1f else 0.4f)
                        )
                    }
                    val sendOff = busy || editing
                    HeaderAction(label = "Kirim email ini sekarang", enabled = !sendOff, onClick = { onSend() }) {
                        when {
                            item.sending -> CircularProgressIndicator(color = GmInk, strokeWidth = 4.dp, modifier = Modifier.size(44.dp))
                            item.sent -> Icon(Icons.Outlined.Done, contentDescription = "Terkirim", tint = GmInk, modifier = Modifier.size(54.dp))
                            else -> Icon(Icons.AutoMirrored.Outlined.Reply, contentDescription = "Kirim", tint = GmInk, modifier = Modifier.size(58.dp).alpha(if (sendOff) 0.4f else 1f))
                        }
                    }
                    Box {
                        HeaderAction(label = "Menu draf email", enabled = true, onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = GmInk, modifier = Modifier.size(56.dp))
                        }
                        CompositionLocalProvider(LocalDensity provides popupDensity) {
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (copied) "Tersalin" else "Salin draf", style = TextStyle(fontFamily = GmFont, fontSize = 15.sp, letterSpacing = 0.sp)) },
                                    leadingIcon = { Icon(if (copied) Icons.Outlined.Done else Icons.Outlined.ContentCopy, contentDescription = null) },
                                    onClick = { clipboard.setText(AnnotatedString(emailDraftText(item))); copied = true; menu = false }
                                )
                            }
                        }
                    }
                }

                // ---- Detail penerima (panah di samping "kepada") ----
                if (expanded && !editing) {
                    Column(Modifier.fillMaxWidth().padding(start = 39.dp, end = 39.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DetailLine("Kepada", item.to.joinToString(", "))
                        if (item.cc.isNotEmpty()) DetailLine("Cc", item.cc.joinToString(", "))
                        DetailLine("Subjek", item.subject)
                    }
                }
                Spacer(Modifier.height(58.dp))

                // ---- Isi surat ----
                if (editing) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 39.dp)) {
                        EditRow("Kepada", toText, { toText = it }, KeyboardType.Email)
                        EditRow("Cc", ccText, { ccText = it }, KeyboardType.Email)
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(GmRule))
                        Box(Modifier.fillMaxWidth().padding(vertical = 26.dp)) {
                            if (subjectText.isEmpty()) BasicText("Subjek", style = gt(27f, 36f, GmGray))
                            BasicTextField(value = subjectText, onValueChange = { subjectText = it }, textStyle = gt(27f, 36f), cursorBrush = SolidColor(GmInk), modifier = Modifier.fillMaxWidth())
                        }
                        Box(Modifier.fillMaxWidth().height(2.dp).background(GmRule))
                        Box(Modifier.fillMaxWidth().heightIn(min = 300.dp).padding(top = 26.dp, bottom = 12.dp)) {
                            if (bodyText.isEmpty()) BasicText("Isi email", style = gt(27f, 43f, GmGray))
                            BasicTextField(value = bodyText, onValueChange = { bodyText = it }, textStyle = gt(27f, 43f), cursorBrush = SolidColor(GmInk), modifier = Modifier.fillMaxWidth())
                        }
                        if (editError.isNotEmpty()) {
                            BasicText(editError, style = gt(24f, 33f, GmError), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            BasicText(
                                "Batal", style = gt(27f, 34f, GmGray),
                                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { editError = ""; editing = false }.padding(horizontal = 34.dp, vertical = 20.dp)
                            )
                            BasicText(
                                "Simpan", style = gt(27f, 34f, Color.White, FontWeight.Medium),
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(GmBlue).clickable(role = Role.Button) { save() }.padding(horizontal = 44.dp, vertical = 20.dp)
                            )
                        }
                    }
                } else {
                    SelectionContainer {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 39.dp)) {
                            blocks.forEachIndexed { i, b ->
                                if (i > 0) {
                                    val prev = blocks[i - 1]
                                    Spacer(Modifier.height(if (b is EmailBodyBlock.Item && prev is EmailBodyBlock.Item) 3.dp else if (b is EmailBodyBlock.Item) 22.dp else 32.dp))
                                }
                                when (b) {
                                    is EmailBodyBlock.Para -> BasicText(b.text, style = gt(27f, 43f), modifier = Modifier.fillMaxWidth())
                                    is EmailBodyBlock.Item -> Row(Modifier.fillMaxWidth().padding(start = 54.dp)) {
                                        Box(Modifier.width(37.dp)) { BasicText(b.marker, style = gt(27f, 43f)) }
                                        BasicText(b.text, style = gt(27f, 43f), modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- Lampiran yang benar-benar terpasang pada draf ----
                if (item.attachments.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(start = 39.dp, end = 39.dp, top = 34.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item.attachments.forEach { p -> AttachmentRow(p, onOpen = { onOpenPath(p, false) }, onShare = { onOpenPath(p, true) }) }
                    }
                }
                Spacer(Modifier.height(44.dp))

                // ---- Bilah lampiran Gmail (x/y dalam satuan acuan, relatif terhadap bilah yang mulai di x = 28) ----
                Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp).height(188.dp).clip(RoundedCornerShape(46.dp)).background(GmBar)) {
                    BarCell(centerX = 120f, label = "Lampirkan\nPDF") {
                        Box(Modifier.size(width = 49.dp, height = 50.dp).clip(RoundedCornerShape(9.dp)).background(GmRed), contentAlignment = Alignment.Center) {
                            BasicText("PDF", style = gt(19f, 24f, Color.White, FontWeight.Bold))
                        }
                    }
                    BarCell(centerX = 344f, label = "Lampirkan\nfile") {
                        Icon(Icons.Outlined.Description, contentDescription = null, tint = GmInk, modifier = Modifier.requiredSize(62.dp))
                    }
                    BarCell(centerX = 564.5f, label = "Lampirkan\ndari Drive") {
                        Image(painterResource(R.drawable.ic_drive_color), contentDescription = null, modifier = Modifier.size(width = 50.dp, height = 45.dp))
                    }
                    Box(Modifier.offset(x = 692.dp, y = 30.dp).size(width = 3.dp, height = 132.dp).background(GmRule))
                    BarCell(centerX = 808f, label = "Lainnya") {
                        Box(Modifier.size(52.dp).border(3.dp, GmInk, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.MoreHoriz, contentDescription = null, tint = GmInk, modifier = Modifier.requiredSize(40.dp))
                        }
                    }
                }

                // ---- Catatan status ----
                val noteStyle = gt(24f, 34f, GmGray, align = TextAlign.Center)
                val notePad = Modifier.fillMaxWidth().padding(start = 39.dp, end = 39.dp, top = 26.dp)
                if (item.note.isNotBlank()) {
                    BasicText(item.note, style = noteStyle.copy(color = GmError), modifier = notePad)
                } else if (item.sent) {
                    BasicText("Email sudah dikirim lewat Gmail Anda.", style = noteStyle, modifier = notePad)
                } else if (item.openedInApp && !item.sending) {
                    BasicText("Aplikasi email dibuka dengan draf ini. Email baru terkirim setelah Anda menekan Kirim di sana.", style = noteStyle, modifier = notePad)
                }
            }
        }
    }
}

/** Kotak sentuh ikon di kepala kartu (116 x 96 satuan acuan). */
@Composable
private fun HeaderAction(label: String, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(width = 116.dp, height = 96.dp).clip(RoundedCornerShape(40.dp))
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Satu baris detail penerima: label abu-abu di kiri, nilai di kanan. */
@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(140.dp)) { BasicText(label, style = gt(27f, 38f, GmGray)) }
        BasicText(value, style = gt(27f, 38f), modifier = Modifier.weight(1f))
    }
}

/** Sel pada bilah lampiran: ikon di atas, label dua baris di bawah, berpusat di [centerX] (satuan acuan, relatif bilah). */
@Composable
private fun BarCell(centerX: Float, label: String, icon: @Composable () -> Unit) {
    Column(Modifier.offset(x = (centerX - 100f).dp).width(200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(36.dp))
        Box(Modifier.height(50.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(21.dp))
        BasicText(label, style = gt(26f, 32f, GmInk, align = TextAlign.Center))
    }
}

/** Lampiran yang terpasang pada draf: kotak ikon sesuai jenis berkas, nama, jenis, dan tombol bagikan. */
@Composable
private fun AttachmentRow(path: String, onOpen: () -> Unit, onShare: () -> Unit) {
    val kind = fileKindOf(path)
    val title = prettyFileName(path)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(GmBar)
            .clickable(onClickLabel = if (kind.shareOnTap) "Bagikan $title" else "Buka $title", role = Role.Button, onClick = onOpen)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(16.dp)).background(kind.bg).border(2.dp, kind.border, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Icon(kind.icon, contentDescription = null, tint = kind.tint, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = gt(27f, 34f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(fileSubtitle(path), style = gt(23f, 30f, GmGray), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(80.dp).clip(CircleShape).clickable(onClickLabel = "Unduh atau bagikan $title", role = Role.Button, onClick = onShare),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Outlined.IosShare, contentDescription = null, tint = GmInk, modifier = Modifier.size(42.dp)) }
    }
}

/** Baris isian saat mode ubah: label di kiri, kolom teks membulat di kanan (beberapa alamat dipisah koma). */
@Composable
private fun EditRow(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(130.dp)) { BasicText(label, style = gt(27f, 36f)) }
        Box(Modifier.weight(1f).clip(RoundedCornerShape(40.dp)).background(GmChip).padding(horizontal = 30.dp, vertical = 20.dp)) {
            BasicTextField(
                value = value, onValueChange = onChange, textStyle = gt(27f, 36f), cursorBrush = SolidColor(GmInk),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
