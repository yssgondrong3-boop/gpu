package com.gpudash.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Suara starter dan brum dibuat langsung lewat kode, tanpa file audio. */
object Sound {
    private const val SR = 22050

    private fun saw(ph: Double): Double = 2.0 * (ph - floor(ph + 0.5))

    private fun play(pcm: ShortArray) {
        Thread {
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / SR + 250)
                track.release()
            } catch (e: Exception) {
            }
        }.start()
    }

    /** Suara starter berputar, 3 detik. */
    fun crank() {
        val n = SR * 3
        val pcm = ShortArray(n)
        var ph = 0.0
        var y = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            ph += 42.0 / SR
            y += 0.15 * (saw(ph) - y)
            val pulse = 0.55 + 0.45 * sin(2 * PI * 9 * t)
            val env = min(1.0, t / 0.05) * min(1.0, (3.0 - t) / 0.1)
            pcm[i] = (y * pulse * env * 0.5 * 32767).toInt().toShort()
        }
        play(pcm)
    }

    private fun revFreq(t: Double): Double {
        val k = doubleArrayOf(0.0, 0.45, 0.8, 1.3, 2.3)
        val f = doubleArrayOf(55.0, 190.0, 80.0, 240.0, 70.0)
        for (i in 1 until k.size) {
            if (t <= k[i]) {
                val u = (t - k[i - 1]) / (k[i] - k[i - 1])
                return f[i - 1] * (f[i] / f[i - 1]).pow(u)
            }
        }
        return f[f.size - 1]
    }

    /** Mesin menyala lalu brum brummm, 2,5 detik. */
    fun rev() {
        val n = SR * 5 / 2
        val pcm = ShortArray(n)
        var ph = 0.0
        var y = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            ph += revFreq(t) / SR
            val x = saw(ph) + 0.4 * saw(2 * ph)
            y += 0.2 * (x - y)
            val tail = if (t < 1.4) 1.0 else max(0.0, (2.4 - t) / 1.0)
            val env = min(1.0, t / 0.1) * tail
            pcm[i] = (y * env * 0.4 * 32767).toInt().toShort()
        }
        play(pcm)
    }
}
