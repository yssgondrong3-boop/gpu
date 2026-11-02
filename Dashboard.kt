package com.gpudash.app

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val BG = Color(0xFF070B14)
private val LC = Color(0xFFDCE8FF)
private val SC = Color(0xFF9DB4E0)
private val PANEL = Color(0xFF0E1730)
private val GHOST = Color(0xFF0F1B38)
private val TICK = Color(0xFF3A5189)
private val NEEDLE = Color(0xFFF2F4F8)
private val RED = Color(0xFFE5483D)

private fun gcol(t: Float): Color {
    val s = arrayOf(
        floatArrayOf(0f, 63f, 207f, 107f), floatArrayOf(.5f, 245f, 205f, 60f),
        floatArrayOf(.75f, 243f, 140f, 50f), floatArrayOf(1f, 229f, 72f, 61f)
    )
    for (i in 1..3) {
        if (t <= s[i][0]) {
            val u = (t - s[i - 1][0]) / (s[i][0] - s[i - 1][0])
            return Color(
                (s[i - 1][1] + (s[i][1] - s[i - 1][1]) * u) / 255f,
                (s[i - 1][2] + (s[i][2] - s[i - 1][2]) * u) / 255f,
                (s[i - 1][3] + (s[i][3] - s[i - 1][3]) * u) / 255f
            )
        }
    }
    return RED
}

private fun pt(cx: Float, cy: Float, r: Float, a: Float): Offset {
    val t = Math.toRadians(a.toDouble())
    return Offset(cx + r * cos(t).toFloat(), cy + r * sin(t).toFloat())
}

private fun DrawScope.gArc(cx: Float, cy: Float, r: Float, f: Float, sw: Float, a0: Float, sp: Float) {
    val n = (sp / 4).roundToInt()
    val tl = Offset(cx - r, cy - r)
    val sz = Size(2 * r, 2 * r)
    for (i in 0 until n) {
        val t = (i + .5f) / n
        drawArc(gcol(t).copy(alpha = .2f), a0 + sp * i / n, sp / n + .5f, false, tl, sz, style = Stroke(sw))
    }
    for (i in 0 until n) {
        val t = (i + .5f) / n
        if (t <= f) drawArc(gcol(t).copy(alpha = .14f), a0 + sp * i / n, sp / n + .5f, false, tl, sz, style = Stroke(sw * 2.1f))
    }
    for (i in 0 until n) {
        val t = (i + .5f) / n
        if (t <= f) drawArc(gcol(t), a0 + sp * i / n, sp / n + .5f, false, tl, sz, style = Stroke(sw))
    }
}

private fun DrawScope.needle(cx: Float, cy: Float, r: Float, f: Float, sw: Float, n: Int, a0: Float, sp: Float, big: Boolean) {
    for (i in 0..n) {
        val a = a0 + sp * i / n
        drawLine(TICK, pt(cx, cy, r - sw - 3, a), pt(cx, cy, r - sw - 3 - (if (big) 8f else 4f), a), 1f)
    }
    val e = pt(cx, cy, r - sw - 4, a0 + sp * f)
    drawLine(NEEDLE, Offset(cx, cy), e, if (big) 3f else 2f, StrokeCap.Round)
    drawCircle(NEEDLE, if (big) 6f else 4f, Offset(cx, cy))
}

private val CODE = mapOf(
    '0' to "abcdef", '1' to "bc", '2' to "abdeg", '3' to "abcdg", '4' to "bcfg",
    '5' to "acdfg", '6' to "acdefg", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg"
)

private fun DrawScope.seg(s: String, x: Float, y: Float, h: Float, al: Char = 'm'): Float {
    val w = h * .55f
    val g = h * .2f
    val t = h * .12f
    val i = t * .9f
    var total = 0f
    for (c in s) total += if (c.isDigit()) w + g else t * 2.6f
    var x0 = when (al) { 'm' -> x - total / 2; 'e' -> x - total; else -> x }
    for (c in s) {
        if (c.isDigit()) {
            val sg = mapOf(
                'a' to floatArrayOf(x0 + i, y, x0 + w - i, y),
                'b' to floatArrayOf(x0 + w, y + i, x0 + w, y + h / 2 - i),
                'c' to floatArrayOf(x0 + w, y + h / 2 + i, x0 + w, y + h - i),
                'd' to floatArrayOf(x0 + i, y + h, x0 + w - i, y + h),
                'e' to floatArrayOf(x0, y + h / 2 + i, x0, y + h - i),
                'f' to floatArrayOf(x0, y + i, x0, y + h / 2 - i),
                'g' to floatArrayOf(x0 + i, y + h / 2, x0 + w - i, y + h / 2)
            )
            val on = CODE[c] ?: ""
            for ((_, v) in sg) drawLine(GHOST, Offset(v[0], v[1]), Offset(v[2], v[3]), t, StrokeCap.Round)
            for ((k, v) in sg) if (on.indexOf(k) >= 0) {
                drawLine(LC.copy(alpha = .18f), Offset(v[0], v[1]), Offset(v[2], v[3]), t * 2.2f, StrokeCap.Round)
                drawLine(LC, Offset(v[0], v[1]), Offset(v[2], v[3]), t, StrokeCap.Round)
            }
            x0 += w + g
        } else {
            val r = t * .8f
            val cx2 = x0 + r + t * .2f
            val ys = if (c == ':') listOf(y + h * .3f, y + h * .7f) else listOf(y + h - r)
            for (yy in ys) drawRect(LC, Offset(cx2 - r, yy - r), Size(2 * r, 2 * r))
            x0 += t * 2.6f
        }
    }
    return x0
}

private fun DrawScope.txt(tm: TextMeasurer, s: String, x: Float, y: Float, size: Float, c: Color, al: Char = 'm', bold: Boolean = false) {
    val st = TextStyle(color = c, fontSize = size.toSp(), fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal)
    val r = tm.measure(s, st)
    val w = r.size.width.toFloat()
    val tx = when (al) { 'm' -> x - w / 2f; 'e' -> x - w; else -> x }
    drawText(r, topLeft = Offset(tx, y - r.firstBaseline))
}

private fun DrawScope.dashboard(tm: TextMeasurer, u: Ui) {
    drawRoundRect(BG, Offset.Zero, Size(380f, 610f), CornerRadius(16f))
    val tc = when { u.throttle >= 95 -> Color(0xFF3FCF6B); u.throttle >= 85 -> Color(0xFFFFB020); else -> RED }
    drawCircle(tc, 5f, Offset(24f, 24f))
    txt(tm, "Throttle ${u.throttle}%", 36f, 29f, 12f, LC, 's')
    seg(String.format(Locale.US, "%02d:%02d", u.elapsedSec / 60, u.elapsedSec % 60), 356f, 14f, 16f, 'e')

    val sc = if (u.scale > 0f) u.scale else 100000f
    val f = (u.score / sc).coerceIn(0f, 1f)
    gArc(190f, 215f, 130f, f, 10f, 180f, 180f)
    needle(190f, 215f, 130f, f, 10f, 16, 180f, 180f, true)
    for (i in 0..4) {
        val p = pt(190f, 215f, 98f, 180f + 45f * i)
        txt(tm, (sc * i / 4 / 1000f).roundToInt().toString(), p.x, p.y + 4f, 12f, SC)
    }
    seg(fmt(u.score.toInt()), 190f, 240f, 26f, 'm')
    txt(tm, "GIPS", 190f, 284f, 11f, SC)

    val nm = listOf("Max", "Avg", "Min")
    val gv = listOf(u.max, u.avg, u.min)
    val tv = listOf(u.tMax, u.tAvg, u.tMin)
    val fv = listOf(u.fMax, u.fAvg, u.fMin)
    for (i in 0..2) {
        val cx = 85f + 105f * i
        txt(tm, nm[i], cx, 300f, 11f, SC)
        seg(fmt(gv[i]), cx, 308f, 16f, 'm')
        val e1 = seg(tv[i].toString(), cx - 17f, 336f, 12f, 's')
        txt(tm, "°C", e1 + 2f, 346f, 10f, SC, 's')
        val e2 = seg(fv[i].toString(), cx - 19f, 356f, 12f, 's')
        txt(tm, "FPS", e2 + 2f, 366f, 10f, SC, 's')
    }

    val cf = (u.coreGhz / (if (u.coreMaxGhz > 0f) u.coreMaxGhz else 3f)).coerceIn(0f, 1f)
    gArc(66f, 450f, 50f, cf, 6f, 150f, 240f)
    needle(66f, 450f, 50f, cf, 6f, 8, 150f, 240f, false)
    val e3 = seg(String.format(Locale.US, "%.2f", u.coreGhz), 58f, 477f, 14f, 'm')
    txt(tm, "GHz", e3 + 2f, 488f, 10f, SC, 's')
    txt(tm, "CPU core ${u.coreIdx + 1}", 66f, 508f, 11f, SC)

    val gf = (u.gpuPct / 100f).coerceIn(0f, 1f)
    gArc(314f, 450f, 50f, gf, 6f, 150f, 240f)
    needle(314f, 450f, 50f, gf, 6f, 8, 150f, 240f, false)
    val e4 = seg(u.gpuPct.toString(), 308f, 477f, 14f, 'm')
    txt(tm, "%", e4 + 2f, 488f, 10f, SC, 's')
    txt(tm, "GPU", 314f, 508f, 11f, SC)

    drawRoundRect(PANEL, Offset(130f, 388f), Size(120f, 112f), CornerRadius(10f))
    val e5 = seg(u.temp.toString(), 180f, 402f, 24f, 'm')
    txt(tm, "°C", e5 + 3f, 424f, 12f, SC, 's')
    txt(tm, "Suhu", 190f, 442f, 11f, SC)
    seg(u.fps.toString(), 190f, 452f, 24f, 'm')
    txt(tm, "FPS", 190f, 494f, 11f, SC)

    drawCircle(PANEL, 28f, Offset(190f, 562f))
    drawCircle(RED, 28f, Offset(190f, 562f), style = Stroke(3f))
    txt(tm, if (u.running) "STOP" else "START", 190f, 567f, 12f, RED, 'm', true)
    drawRoundRect(PANEL, Offset(24f, 546f), Size(92f, 32f), CornerRadius(16f))
    txt(tm, "Riwayat", 70f, 567f, 12f, LC)
    drawRoundRect(PANEL, Offset(264f, 546f), Size(92f, 32f), CornerRadius(16f))
    txt(tm, "Bagikan", 310f, 567f, 12f, LC)
}

@Composable
private fun GpuLoad() {
    val t by produceState(0f) { while (true) { withFrameNanos { value = it / 1e9f } } }
    Canvas(Modifier.fillMaxSize().alpha(0.04f)) {
        for (i in 0 until 250) {
            val a = t * 0.7f + i
            val c = Offset(size.width * (0.5f + 0.45f * sin(a * 1.3f + i)), size.height * (0.5f + 0.45f * cos(a * 0.9f + i * 2f)))
            drawCircle(Brush.radialGradient(listOf(Color.Cyan, Color.Transparent), c, 140f), 140f, c)
        }
    }
}

@Composable
private fun HistoryView(runs: List<Run>, onClose: () -> Unit) {
    val df = SimpleDateFormat("dd MMM HH:mm", Locale.US)
    val s1 = TextStyle(color = SC, fontSize = 13.sp)
    val s2 = TextStyle(color = LC, fontSize = 15.sp)
    Column(Modifier.fillMaxSize().background(BG).clickable { onClose() }.padding(20.dp).verticalScroll(rememberScrollState())) {
        BasicText("Riwayat · ketuk untuk tutup", style = s1)
        Spacer(Modifier.height(12.dp))
        if (runs.isEmpty()) BasicText("Belum ada hasil tes.", style = s2)
        for (r in runs.reversed()) {
            BasicText(df.format(Date(r.time)), style = s1)
            BasicText("Avg ${fmt(r.avg)} · Max ${fmt(r.max)} · Min ${fmt(r.min)}", style = s2)
            BasicText("${r.tAvg}°C · ${r.fAvg} FPS", style = s1)
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
fun Dashboard(vm: DashVM) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val tm = rememberTextMeasurer()
    val ctx = LocalContext.current
    var hist by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        var n = 0
        var t0 = 0L
        while (true) {
            withFrameNanos { ts ->
                if (t0 == 0L) t0 = ts
                n++
                if (ts - t0 >= 1_000_000_000L) { vm.onFps(n); n = 0; t0 = ts }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(BG)) {
        if (ui.running) GpuLoad()
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(380f / 610f).align(Alignment.Center)
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val k = size.width / 380f
                        val x = o.x / k
                        val y = o.y / k
                        when {
                            hypot(x - 190f, y - 562f) < 34f -> vm.toggle()
                            hypot(x - 66f, y - 450f) < 58f -> vm.nextCore()
                            x in 24f..116f && y in 546f..578f -> hist = true
                            x in 264f..356f && y in 546f..578f -> {
                                val i = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, vm.shareText())
                                }
                                ctx.startActivity(Intent.createChooser(i, null))
                            }
                        }
                    }
                }
        ) {
            val k = size.width / 380f
            withTransform({ scale(k, k, Offset.Zero) }) { dashboard(tm, ui) }
        }
        if (hist) {
            val runs = remember { History.list(ctx) }
            HistoryView(runs) { hist = false }
        }
    }
}
