package com.gpudash.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class Ui(
    val running: Boolean = false, val countdown: Int = 0, val go: Boolean = false,
    val elapsedSec: Int = 0,
    val score: Float = 0f, val scale: Float = 0f,
    val max: Int = 0, val avg: Int = 0, val min: Int = 0,
    val tMax: Int = 0, val tAvg: Int = 0, val tMin: Int = 0,
    val fMax: Int = 0, val fAvg: Int = 0, val fMin: Int = 0,
    val throttle: Int = 100,
    val cores: List<Float> = emptyList(), val coreMaxGhz: Float = 3f,
    val gpuPct: Int = 0, val temp: Int = 0, val fps: Int = 0,
    val hist: List<Float> = emptyList()
)

data class Run(
    val time: Long, val max: Int, val avg: Int, val min: Int,
    val tMax: Int, val tAvg: Int, val tMin: Int,
    val fMax: Int, val fAvg: Int, val fMin: Int
)

object History {
    private fun sp(c: Context) = c.getSharedPreferences("hist", Context.MODE_PRIVATE)

    fun list(c: Context): List<Run> =
        (sp(c).getString("runs", "") ?: "").lines().filter { it.isNotBlank() }.mapNotNull { l ->
            val p = l.split(",")
            if (p.size < 10) null else try {
                Run(p[0].toLong(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt(),
                    p[6].toInt(), p[7].toInt(), p[8].toInt(), p[9].toInt())
            } catch (e: Exception) { null }
        }

    fun add(c: Context, r: Run) {
        val cur = (sp(c).getString("runs", "") ?: "").lines().filter { it.isNotBlank() }
        val line = listOf(r.time, r.max, r.avg, r.min, r.tMax, r.tAvg, r.tMin, r.fMax, r.fAvg, r.fMin).joinToString(",")
        sp(c).edit().putString("runs", (cur + line).takeLast(50).joinToString("\n")).apply()
    }
}

fun fmt(n: Int): String = String.format(Locale.US, "%,d", n)

class DashVM(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val counter = AtomicLong(0)
    private val lock = Any()
    private val scores = ArrayList<Float>()
    private val temps = ArrayList<Float>()
    private val fpss = ArrayList<Int>()
    @Volatile private var stop = true
    @Volatile private var sink = 0L
    @Volatile private var fpsNow = 0
    @Volatile private var skipNext = false
    @Volatile var refreshRate = 60f
    private var startNs = 0L
    private var workers: List<Thread> = emptyList()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            var lastN = 0L
            var lastT = System.nanoTime()
            var tick = 0
            while (isActive) {
                delay(1000)
                tick++
                val now = System.nanoTime()
                val n = counter.get()
                val st = _ui.value
                val n8 = min(Sensors.coreCount, 8)
                val cores = List(n8) { Sensors.coreFreqGhz(it) ?: 0f }
                val mx = (0 until n8).mapNotNull { Sensors.coreMaxGhz(it) }.maxOrNull()
                    ?: max(cores.maxOrNull() ?: 0f, 3f)
                val temp = Sensors.cpuTemp(ctx).roundToInt()
                val gpu = Sensors.gpuLoad()
                    ?: if (st.running) (fpsNow * 100f / refreshRate).roundToInt().coerceIn(0, 100) else 0
                var s = st.copy(cores = cores, coreMaxGhz = mx, gpuPct = gpu, temp = temp, fps = fpsNow)
                if (st.running) {
                    if (skipNext) {
                        skipNext = false
                    } else {
                        val dt = (now - lastT) / 1e9f
                        val score = ((n - lastN) / dt) / 1e5f
                        synchronized(lock) {
                            scores.add(score); temps.add(temp.toFloat()); fpss.add(fpsNow)
                            val mxI = scores.indices.maxByOrNull { scores[it] }!!
                            val mnI = scores.indices.minByOrNull { scores[it] }!!
                            val peak = scores[mxI]
                            var scale = st.scale
                            if (scale <= 0f || score > scale) scale = nice(max(score, peak) * 1.15f)
                            val last5 = scores.takeLast(5).average().toFloat()
                            s = s.copy(
                                score = score, scale = scale,
                                max = peak.toInt(), avg = scores.average().toInt(), min = scores[mnI].toInt(),
                                tMax = temps[mxI].toInt(), tAvg = temps.average().toInt(), tMin = temps[mnI].toInt(),
                                fMax = fpss[mxI], fAvg = fpss.average().roundToInt(), fMin = fpss[mnI],
                                throttle = (100 * last5 / peak).roundToInt().coerceIn(0, 100),
                                hist = scores.takeLast(60).toList()
                            )
                        }
                    }
                    s = s.copy(elapsedSec = ((now - startNs) / 1_000_000_000L).toInt())
                }
                lastN = n
                lastT = now
                _ui.update { s.copy(running = it.running, countdown = it.countdown, go = it.go) }
                if (tick % 5 == 0) DashWidget.refresh(ctx)
            }
        }
    }

    private fun nice(x: Float): Float {
        if (x <= 0f) return 100000f
        val step = 10.0.pow(floor(log10(x.toDouble()))) / 2
        return (ceil(x / step) * step).toFloat()
    }

    fun onFps(f: Int) { fpsNow = f }

    /** Ketuk START: hitung mundur 3-2-1 dengan suara starter, lalu GO dan tes dimulai. */
    fun toggle() {
        val u = _ui.value
        if (u.running) { stopRun(); return }
        if (u.countdown > 0 || u.go) return
        viewModelScope.launch {
            Sound.crank()
            for (n in 3 downTo 1) {
                _ui.update { it.copy(countdown = n) }
                delay(1000)
            }
            _ui.update { it.copy(countdown = 0, go = true) }
            Sound.rev()
            delay(700)
            _ui.update { it.copy(go = false) }
            startRun()
        }
    }

    private fun startRun() {
        synchronized(lock) { scores.clear(); temps.clear(); fpss.clear() }
        skipNext = true
        stop = false
        counter.set(0)
        startNs = System.nanoTime()
        workers = List(Sensors.coreCount) {
            Thread {
                var x = 88172645463325252L
                while (!stop) {
                    var i = 0
                    while (i < 200000) {
                        x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17); i++
                    }
                    counter.addAndGet(600000L)
                }
                sink = x
            }.also { it.isDaemon = true; it.start() }
        }
        _ui.update {
            it.copy(running = true, scale = 0f, score = 0f, max = 0, avg = 0, min = 0,
                tMax = 0, tAvg = 0, tMin = 0, fMax = 0, fAvg = 0, fMin = 0, throttle = 100,
                elapsedSec = 0, hist = emptyList())
        }
    }

    private fun stopRun() {
        stop = true
        val u = _ui.value
        synchronized(lock) {
            if (scores.size >= 3) History.add(ctx, Run(System.currentTimeMillis(), u.max, u.avg, u.min,
                u.tMax, u.tAvg, u.tMin, u.fMax, u.fAvg, u.fMin))
        }
        _ui.update { it.copy(running = false) }
        DashWidget.refresh(ctx)
    }

    fun shareText(): String {
        val u = _ui.value
        return "GPU Dash\nSkor Max/Avg/Min: ${fmt(u.max)} / ${fmt(u.avg)} / ${fmt(u.min)}\n" +
            "Suhu: ${u.tMax}/${u.tAvg}/${u.tMin} °C\nFPS: ${u.fMax}/${u.fAvg}/${u.fMin}\nThrottle: ${u.throttle}%"
    }

    override fun onCleared() { stop = true }
}
