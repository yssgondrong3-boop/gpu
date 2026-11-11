package com.gpudash.app

import android.content.Intent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
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
import kotlin.math.min
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
private val ROAD = Color(0xFF1E2B50)

private class Anim { var ang = 0f; var off = 0f; var last = 0L }

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

// ---- busur gradasi: satu gambar per busur, dipakai ulang (ringan) ----
private val sweepCache = HashMap<String, Brush>()
private fun sweepBrush(cx: Float, cy: Float, sp: Float): Brush = sweepCache.getOrPut("$cx,$cy,$sp") {
    val e = sp / 360f
    Brush.sweepGradient(
        0f to gcol(0f), e * .5f to gcol(.5f), e * .75f to gcol(.75f), e to gcol(1f), 1f to gcol(1f),
        center = Offset(cx, cy)
    )
}

private fun DrawScope.gauge(cx: Float, cy: Float, r: Float, f: Float, sw: Float, a0: Float, sp: Float, n: Int, hub: Float) {
    val tl = Offset(cx - r, cy - r)
    val sz = Size(2 * r, 2 * r)
    val br = sweepBrush(cx, cy, sp)
    val ff = f.coerceIn(0f, 1.04f)
    rotate(a0, Offset(cx, cy)) {
        drawArc(br, 0f, sp, false, tl, sz, alpha = .2f, style = Stroke(sw))
        if (ff > 0.005f) {
            drawArc(br, 0f, sp * ff, false, tl, sz, alpha = .14f, style = Stroke(sw * 2.1f))
            drawArc(br, 0f, sp * ff, false, tl, sz, style = Stroke(sw))
        }
    }
    val tick = if (hub > 5f) 8f else 4f
    for (i in 0..n) {
        val a = a0 + sp * i / n
        drawLine(TICK, pt(cx, cy, r - sw - 3, a), pt(cx, cy, r - sw - 3 - tick, a), 1f)
    }
    drawLine(NEEDLE, Offset(cx, cy), pt(cx, cy, r - sw - 4, a0 + sp * ff), if (hub > 5f) 3f else 2f, StrokeCap.Round)
    drawCircle(NEEDLE, hub, Offset(cx, cy))
}

// ---- angka digital tujuh segmen, bentuknya di-cache ----
private val CODE = mapOf(
    '0' to "abcdef", '1' to "bc", '2' to "abdeg", '3' to "abcdg", '4' to "bcfg",
    '5' to "acdfg", '6' to "acdefg", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg"
)

private class SegP(val ghost: Path, val lit: Path, val dots: Path, val t: Float, val end: Float)

private val segCache = HashMap<String, SegP>()

private fun buildSeg(s: String, h: Float, al: Char): SegP {
    val w = h * .55f
    val g = h * .2f
    val t = h * .12f
    val i = t * .9f
    var tot = 0f
    for (c in s) tot += if (c.isDigit()) w + g else t * 2.6f
    var x0 = when (al) { 'm' -> -tot / 2; 'e' -> -tot; else -> 0f }
    val ghost = Path()
    val lit = Path()
    val dots = Path()
    for (c in s) {
        if (c.isDigit()) {
            val on = CODE[c] ?: ""
            fun ln(k: Char, x1: Float, y1: Float, x2: Float, y2: Float) {
                ghost.moveTo(x1, y1); ghost.lineTo(x2, y2)
                if (on.indexOf(k) >= 0) { lit.moveTo(x1, y1); lit.lineTo(x2, y2) }
            }
            ln('a', x0 + i, 0f, x0 + w - i, 0f)
            ln('b', x0 + w, i, x0 + w, h / 2 - i)
            ln('c', x0 + w, h / 2 + i, x0 + w, h - i)
            ln('d', x0 + i, h, x0 + w - i, h)
            ln('e', x0, h / 2 + i, x0, h - i)
            ln('f', x0, i, x0, h / 2 - i)
            ln('g', x0 + i, h / 2, x0 + w - i, h / 2)
            x0 += w + g
        } else {
            val r = t * .8f
            val cx = x0 + r + t * .2f
            val ys = if (c == ':') floatArrayOf(h * .3f, h * .7f) else floatArrayOf(h - r)
            for (yy in ys) dots.addRect(Rect(cx - r, yy - r, cx + r, yy + r))
            x0 += t * 2.6f
        }
    }
    return SegP(ghost, lit, dots, t, x0)
}

private fun DrawScope.seg(s: String, x: Float, y: Float, h: Float, al: Char = 'm'): Float {
    if (segCache.size > 400) segCache.clear()
    val sp = segCache.getOrPut("$s|$h|$al") { buildSeg(s, h, al) }
    translate(x, y) {
        drawPath(sp.ghost, GHOST, style = Stroke(sp.t, cap = StrokeCap.Round))
        drawPath(sp.lit, LC.copy(alpha = .18f), style = Stroke(sp.t * 2.2f, cap = StrokeCap.Round))
        drawPath(sp.lit, LC, style = Stroke(sp.t, cap = StrokeCap.Round))
        drawPath(sp.dots, LC)
    }
    return x + sp.end
}

private fun DrawScope.txt(tm: TextMeasurer, s: String, x: Float, y: Float, size: Float, c: Color, al: Char = 'm', bold: Boolean = false) {
    val st = TextStyle(color = c, fontSize = size.toSp(), fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal)
    val r = tm.measure(s, st)
    val w = r.size.width.toFloat()
    val tx = when (al) { 'm' -> x - w / 2f; 'e' -> x - w; else -> x }
    drawText(r, topLeft = Offset(tx, y - r.firstBaseline))
}

// ---- mobil ----
private val carBody = Path().apply {
    moveTo(2f, 34f); lineTo(6f, 25f); quadraticBezierTo(10f, 21f, 26f, 19f); lineTo(50f, 5f)
    quadraticBezierTo(56f, 1f, 66f, 1f); lineTo(104f, 1f); quadraticBezierTo(116f, 1f, 124f, 10f)
    lineTo(140f, 18f); quadraticBezierTo(160f, 20f, 166f, 27f); lineTo(168f, 36f); lineTo(2f, 36f); close()
}
private val carWin = Path().apply {
    moveTo(56f, 18f); lineTo(69f, 5f); lineTo(100f, 5f); lineTo(113f, 18f); close()
}

private fun DrawScope.car(f: Float, ang: Float, off: Float, bob: Float) {
    val c = gcol(f.coerceIn(0f, 1f))
    drawLine(ROAD, Offset(20f, 104f), Offset(360f, 104f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), off + 28f))
    val len = 4f + f.coerceIn(0f, 1f) * 30f
    for (i in 0..2) {
        val y = 62f + i * 12f
        drawLine(c.copy(alpha = .5f), Offset(52f - len * (1f - i * .2f), y), Offset(52f, y), 2f, StrokeCap.Round)
    }
    translate(54f, 49f + bob) {
        drawPath(carBody, c)
        drawPath(carWin, PANEL)
        for (wx in floatArrayOf(34f, 136f)) {
            rotate(ang, Offset(wx, 38f)) {
                drawCircle(BG, 11f, Offset(wx, 38f))
                drawCircle(Color(0xFF4A5C90), 11f, Offset(wx, 38f), style = Stroke(2f))
                drawLine(Color(0xFF6A7DB0), Offset(wx - 8f, 38f), Offset(wx + 8f, 38f), 1f)
                drawLine(Color(0xFF6A7DB0), Offset(wx, 30f), Offset(wx, 46f), 1f)
            }
        }
        drawCircle(Color(0xFFFFE08A), 3f, Offset(164f, 27f))
    }
}

private fun nm(k: Int): String = when {
    k < 40 -> "sepeda motor"
    k < 80 -> "mobil di kota"
    k < 120 -> "mobil di jalan tol"
    k < 180 -> "mobil sport"
    k < 240 -> "supercar"
    k < 300 -> "motor balap MotoGP"
    else -> "kereta cepat"
}

private fun buildGraph(h: List<Float>, sc: Float): Pair<Path, Path>? {
    if (h.size < 2) return null
    val line = Path()
    val area = Path()
    area.moveTo(28f, 802f)
    h.forEachIndexed { i, v ->
        val x = 28f + 324f * i / 59f
        val y = 802f - 42f * (v / sc).coerceIn(0f, 1f)
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        area.lineTo(x, y)
    }
    area.lineTo(28f + 324f * (h.size - 1) / 59f, 802f)
    area.close()
    return line to area
}

private fun DrawScope.dashboard(
    tm: TextMeasurer, u: Ui, an: Anim, tk: Float, fm: Float, fg: Float,
    fcs: FloatArray, sMain: Float, sc: Float, gr: Pair<Path, Path>?
) {
    drawRoundRect(BG, Offset.Zero, Size(380f, 860f), CornerRadius(16f))

    // panel mobil
    drawRoundRect(PANEL, Offset(10f, 10f), Size(360f, 112f), CornerRadius(12f))
    val kmh = (fm.coerceAtLeast(0f) * 320f).roundToInt()
    val bob = sin(tk * 5.5f) * min(1.2f, fm.coerceAtLeast(0f) * 1.5f)
    car(fm, an.ang, an.off, bob)
    seg(kmh.toString(), 296f, 32f, 34f, 'm')
    txt(tm, "km/j", 296f, 84f, 11f, SC)
    txt(tm, "setara ${nm(kmh)}", 296f, 102f, 10f, LC)

    // baris atas
    val tc = when { u.throttle >= 95 -> Color(0xFF3FCF6B); u.throttle >= 85 -> Color(0xFFFFB020); else -> RED }
    drawCircle(tc, 5f, Offset(24f, 150f))
    txt(tm, "Throttle ${u.throttle}%", 36f, 155f, 12f, LC, 's')
    seg(String.format(Locale.US, "%02d:%02d", u.elapsedSec / 60, u.elapsedSec % 60), 356f, 142f, 16f, 'e')

    // speedometer utama
    gauge(190f, 290f, 112f, fm, 9f, 180f, 180f, 16, 6f)
    for (i in 0..4) {
        val p = pt(190f, 290f, 84f, 180f + 45f * i)
        txt(tm, (sc * i / 4 / 1000f).roundToInt().toString(), p.x, p.y + 4f, 11f, SC)
    }
    seg(fmt(sMain.toInt()), 190f, 308f, 24f, 'm')
    txt(tm, "GIPS", 190f, 346f, 11f, SC)

    // Max / Avg / Min
    val names = listOf("Max", "Avg", "Min")
    val gv = listOf(u.max, u.avg, u.min)
    val tv = listOf(u.tMax, u.tAvg, u.tMin)
    val fv = listOf(u.fMax, u.fAvg, u.fMin)
    for (i in 0..2) {
        val cx = 85f + 105f * i
        txt(tm, names[i], cx, 368f, 11f, SC)
        seg(fmt(gv[i]), cx, 374f, 15f, 'm')
        seg(tv[i].toString(), cx - 17f, 396f, 11f, 's')
        txt(tm, "°C", cx + 1f, 407f, 9f, SC, 's')
        seg(fv[i].toString(), cx - 17f, 414f, 11f, 's')
        txt(tm, "FPS", cx + 1f, 425f, 9f, SC, 's')
    }

    // GPU, suhu + FPS, tombol start
    gauge(66f, 490f, 44f, fg, 6f, 150f, 240f, 8, 5f)
    val eg = seg(u.gpuPct.toString(), 62f, 502f, 13f, 'm')
    txt(tm, "%", eg + 2f, 515f, 10f, SC, 's')
    txt(tm, "GPU", 66f, 548f, 11f, SC)
    drawRoundRect(PANEL, Offset(130f, 438f), Size(120f, 110f), CornerRadius(10f))
    val et = seg(u.temp.toString(), 178f, 450f, 24f, 'm')
    txt(tm, "°C", et + 3f, 472f, 12f, SC, 's')
    txt(tm, "Suhu", 190f, 490f, 11f, SC)
    seg(u.fps.toString(), 190f, 500f, 24f, 'm')
    txt(tm, "FPS", 190f, 540f, 11f, SC)
    drawCircle(PANEL, 34f, Offset(314f, 490f))
    drawCircle(RED, 34f, Offset(314f, 490f), style = Stroke(3f))
    txt(tm, if (u.running) "STOP" else "START", 314f, 495f, 13f, RED, 'm', true)

    // 8 gauge core kecil
    txt(tm, "CPU core · GHz", 24f, 572f, 10f, SC, 's')
    for (i in 0 until 8) {
        val cx = 56f + 89f * (i % 4)
        val cy = 612f + 74f * (i / 4)
        gauge(cx, cy, 27f, fcs[i], 4f, 150f, 240f, 6, 3f)
        val ghz = u.cores.getOrNull(i) ?: 0f
        txt(tm, String.format(Locale.US, "%.1f", ghz), cx, cy + 17f, 9f, LC, 'm', true)
        txt(tm, "C${i + 1}", cx, cy + 38f, 9f, SC)
    }

    // grafik GIPS
    drawRoundRect(PANEL, Offset(16f, 738f), Size(348f, 72f), CornerRadius(10f))
    txt(tm, "Grafik GIPS · 60 detik", 28f, 754f, 10f, SC, 's')
    for (y in floatArrayOf(760f, 781f, 802f)) drawLine(ROAD, Offset(28f, y), Offset(352f, y), 1f)
    txt(tm, "${(sc / 1000f).roundToInt()}k", 352f, 757f, 9f, SC, 'e')
    if (gr != null && u.hist.isNotEmpty()) {
        val gc = gcol((u.hist.last() / sc).coerceIn(0f, 1f))
        drawPath(gr.second, gc.copy(alpha = .18f))
        drawPath(gr.first, gc, style = Stroke(2f, join = StrokeJoin.Round))
    }

    // tombol bawah
    drawRoundRect(PANEL, Offset(24f, 820f), Size(100f, 30f), CornerRadius(15f))
    txt(tm, "Riwayat", 74f, 840f, 12f, LC)
    txt(tm, "v3.0", 190f, 840f, 10f, SC)
    drawRoundRect(PANEL, Offset(256f, 820f), Size(100f, 30f), CornerRadius(15f))
    txt(tm, "Bagikan", 306f, 840f, 12f, LC)

    // hitung mundur
    if (u.countdown > 0 || u.go) {
        drawRoundRect(BG.copy(alpha = .86f), Offset.Zero, Size(380f, 860f), CornerRadius(16f))
        if (u.countdown > 0) {
            seg(u.countdown.toString(), 190f, 300f, 140f, 'm')
            txt(tm, "Menyalakan mesin…", 190f, 490f, 13f, SC)
        } else {
            txt(tm, "GO", 190f, 410f, 96f, Color(0xFF3FCF6B), 'm', true)
            txt(tm, "Brummm!", 190f, 490f, 13f, SC)
        }
    }
}

@Composable
private fun GpuLoad() {
    val t by produceState(0f) { while (true) { withFrameNanos { value = it / 1e9f } } }
    Canvas(Modifier.fillMaxSize().alpha(0.04f)) {
        for (i in 0 until 200) {
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
    val tm = rememberTextMeasurer(cacheSize = 128)
    val ctx = LocalContext.current
    var hist by remember { mutableStateOf(false) }
    val anim = remember { Anim() }
    val tick = remember { mutableLongStateOf(0L) }

    val spec = spring<Float>(dampingRatio = 0.72f, stiffness = 70f)
    val sc = if (ui.scale > 0f) ui.scale else 100000f
    val cm = if (ui.coreMaxGhz > 0f) ui.coreMaxGhz else 3f
    val fMain = animateFloatAsState((ui.score / sc).coerceIn(0f, 1f), spec, label = "main")
    val fGpu = animateFloatAsState((ui.gpuPct / 100f).coerceIn(0f, 1f), spec, label = "gpu")
    val fCores = (0 until 8).map { i ->
        animateFloatAsState(((ui.cores.getOrNull(i) ?: 0f) / cm).coerceIn(0f, 1f), spec, label = "core$i")
    }
    val sMain = animateFloatAsState(ui.score, tween(800, easing = FastOutSlowInEasing), label = "score")
    val graph = remember(ui.hist, sc) { buildGraph(ui.hist, sc) }

    LaunchedEffect(Unit) {
        var n = 0
        var t0 = 0L
        while (true) {
            withFrameNanos { ts ->
                if (anim.last != 0L) {
                    val dt = (ts - anim.last) / 1e9f
                    val k = fMain.value.coerceAtLeast(0f) * 320f
                    anim.ang = (anim.ang + k * dt * 5f) % 360f
                    anim.off = (anim.off - k * dt * .9f) % 28f
                }
                anim.last = ts
                tick.longValue = ts
                if (t0 == 0L) t0 = ts
                n++
                if (ts - t0 >= 1_000_000_000L) { vm.onFps(n); n = 0; t0 = ts }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(BG)) {
        if (ui.running) GpuLoad()
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(380f / 860f).align(Alignment.Center)
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val k = size.width / 380f
                        val x = o.x / k
                        val y = o.y / k
                        when {
                            hypot(x - 314f, y - 490f) < 40f -> vm.toggle()
                            x in 24f..124f && y in 820f..850f -> hist = true
                            x in 256f..356f && y in 820f..850f -> {
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
            val tk = tick.longValue / 1e9f
            val fcs = FloatArray(8) { fCores[it].value }
            withTransform({ scale(k, k, Offset.Zero) }) {
                dashboard(tm, ui, anim, tk, fMain.value, fGpu.value, fcs, sMain.value, sc, graph)
            }
        }
        if (hist) {
            val runs = remember { History.list(ctx) }
            HistoryView(runs) { hist = false }
        }
    }
}
