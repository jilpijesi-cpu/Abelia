package com.mythos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val SliderBlue: Color get() = androidx.compose.ui.graphics.lerp(Color(0xFF1A6DFF), Color(0xFF0A45C4), FontTheme.thinBoost)
private val TrackGrey = Color(0xFFEAEAEA)
private val DotGrey = Color(0xFFBDBDBD)

/**
 * Slider kustom bergaya pengaturan sistem: jalur abu-abu, bagian aktif biru, titik pemberhentian opsional, dan kenop cincin.
 * [fraction] 0..1; [stops] = jumlah langkah (mis. 4 untuk 5 titik), 0 = mulus.
 */
@Composable
internal fun MythosSlider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    description: String,
    valueText: String,
    modifier: Modifier = Modifier,
    stops: Int = 0,
) {
    val latest by rememberUpdatedState(onFractionChange)
    val f = fraction.coerceIn(0f, 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics {
                contentDescription = description
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f, if (stops > 1) stops - 1 else 0)
                setProgress { v -> latest(v.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                val pad = 12.dp.toPx()
                detectTapGestures { o -> latest(((o.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                val pad = 12.dp.toPx()
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    latest(((change.position.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f))
                }
            }
    ) {
        val pad = 12.dp.toPx()
        val cy = size.height / 2f
        val x0 = pad
        val x1 = size.width - pad
        val tx = x0 + (x1 - x0) * f
        val stroke = 4.dp.toPx()
        drawLine(TrackGrey, Offset(x0, cy), Offset(x1, cy), stroke, StrokeCap.Round)
        drawLine(SliderBlue, Offset(x0, cy), Offset(tx, cy), stroke, StrokeCap.Round)
        if (stops > 0) {
            for (i in 0..stops) {
                val x = x0 + (x1 - x0) * i / stops
                drawCircle(if (x <= tx + 0.5f) SliderBlue else DotGrey, 4.dp.toPx(), Offset(x, cy))
            }
        }
        drawCircle(SliderBlue, 11.dp.toPx(), Offset(tx, cy))
        drawCircle(Color.White, 6.dp.toPx(), Offset(tx, cy))
    }
}

@Composable
private fun SliderHeader(title: String, atDefault: Boolean, defaultText: String, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = TextStyle(fontFamily = MythosSans, fontSize = 18.sp, color = MythosColors.Ink), modifier = Modifier.weight(1f))
        if (atDefault) {
            Text(defaultText, style = TextStyle(fontFamily = MythosSans, fontSize = 14.sp, color = MythosColors.Hint))
        } else {
            Text(
                "Kembalikan",
                style = TextStyle(fontFamily = MythosSans, fontSize = 14.sp, color = SliderBlue),
                modifier = Modifier
                    .clickable(onClickLabel = "Kembalikan $title ke bawaan", role = Role.Button) { onReset() }
                    .padding(vertical = 6.dp)
            )
        }
    }
}

/** Baris "Ukuran Font": 5 langkah (Kecil, Default, Besar, Sangat Besar, Terbesar) seperti pengaturan font sistem. Hanya berlaku di aplikasi ini. */
@Composable
internal fun FontSizeRow(step: Int, onChange: (Int) -> Unit) {
    val last = FontScale.scales.lastIndex
    val s = step.coerceIn(0, last)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        SliderHeader("Ukuran Font", s == FontScale.DEFAULT_STEP, FontScale.labels[s]) { onChange(FontScale.DEFAULT_STEP) }
        MythosSlider(
            fraction = s.toFloat() / last,
            onFractionChange = { onChange((it * last).roundToInt()) },
            description = "Ukuran Font",
            valueText = FontScale.labels[s],
            stops = last,
        )
    }
}

/** Baris "Ketebalan Font": slider mulus; Default ada di sekitar kiri tengah, kiri = lebih tipis, kanan = lebih tebal. */
@Composable
internal fun FontWeightRow(adj: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)) {
        SliderHeader("Ketebalan Font", adj == 0, "Default") { onChange(0) }
        MythosSlider(
            fraction = FontTheme.adjToFraction(adj),
            onFractionChange = { onChange(FontTheme.fractionToAdj(it)) },
            description = "Ketebalan Font",
            valueText = if (adj == 0) "Default" else if (adj < 0) "Lebih tipis" else "Lebih tebal",
        )
    }
}

/**
 * Pratinjau teks langsung: membaca gaya font, ukuran, dan ketebalan dari state yang sama dengan seluruh aplikasi
 * (lewat [MythosSans] dan skala kepadatan di MythosTheme), jadi berubah seketika saat pengaturan digeser.
 */
@Composable
internal fun FontPreviewCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .background(MythosColors.Card, RoundedCornerShape(16.dp))
            .border(1.dp, MythosColors.Border, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            "Pratinjau",
            style = TextStyle(fontFamily = MythosSans, fontSize = 12.sp, color = MythosColors.Hint)
        )
        Text(
            "Halo, ada yang bisa dibantu?",
            style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, color = MythosColors.Ink),
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            "Seperti ini tampilan teks jawaban di aplikasi. Ukuran, gaya font, dan ketebalan yang kamu pilih langsung terlihat di sini.",
            style = bodyStyle(),
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
