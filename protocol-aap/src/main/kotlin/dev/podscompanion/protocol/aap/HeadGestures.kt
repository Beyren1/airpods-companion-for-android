package dev.podscompanion.protocol.aap

import kotlin.math.abs

/** Жест головой. */
enum class HeadGesture { NOD, SHAKE }

/**
 * Итог калибровки. Наушники присылают шесть чисел (гироскоп и сила тяжести) без подписи, поэтому один раз просим
 * кивнуть и покачать головой и запоминаем, какой угол при этом менялся сильнее всего.
 * Порог — половина размаха, который получился при калибровке.
 */
data class HeadCalibration(
    val nodAxis: Int,
    val shakeAxis: Int,
    val nodThreshold: Int,
    val shakeThreshold: Int,
)

/**
 * Собирает углы и «разворачивает» их: значение 16-битное, и при переходе через край
 * (32767 → −32768) без разворота размах стал бы огромным.
 */
class AxisRecorder {
    private val last = IntArray(AXES)
    private val offset = IntArray(AXES)
    private var started = false
    private val samples = ArrayList<IntArray>()

    val size get() = samples.size

    fun add(orientation: List<Int>): IntArray {
        val unwrapped = IntArray(AXES) { axis ->
            val raw = orientation.getOrElse(axis) { 0 }
            if (started) {
                val delta = raw - last[axis]
                if (delta > HALF) offset[axis] -= FULL
                if (delta < -HALF) offset[axis] += FULL
            }
            last[axis] = raw
            raw + offset[axis]
        }
        started = true
        samples += unwrapped
        return unwrapped
    }

    /** Размах (max − min) по каждой оси. */
    fun ranges(): IntArray = IntArray(AXES) { axis ->
        if (samples.isEmpty()) 0 else samples.maxOf { it[axis] } - samples.minOf { it[axis] }
    }

    companion object {
        const val AXES = 6
        private const val FULL = 65_536
        private const val HALF = 32_768
    }
}

object HeadCalibrator {
    /** Меньше этого размаха — человек не двигал головой или датчики не пришли. */
    const val MIN_RANGE = 200

    /** null — движение не распознано, калибровку надо повторить. */
    fun calibrate(nod: AxisRecorder, shake: AxisRecorder): HeadCalibration? {
        val nodRanges = nod.ranges()
        val nodAxis = nodRanges.indices.maxBy { nodRanges[it] }
        val shakeRanges = shake.ranges()
        val shakeAxis = shakeRanges.indices.filter { it != nodAxis }.maxBy { shakeRanges[it] }
        if (nodRanges[nodAxis] < MIN_RANGE || shakeRanges[shakeAxis] < MIN_RANGE) return null
        return HeadCalibration(nodAxis, shakeAxis, nodRanges[nodAxis] / 2, shakeRanges[shakeAxis] / 2)
    }
}

/**
 * Распознаёт кивок и покачивание по потоку углов. Жест — когда по оси за [windowMs] голова
 * дважды сменила направление движения (туда и обратно) с размахом не меньше порога, и эта ось
 * двигалась заметно сильнее другой. После жеста [cooldownMs] ничего не распознаём, чтобы один
 * кивок не сработал дважды.
 */
class HeadGestureDetector(
    private val calibration: HeadCalibration,
    private val windowMs: Long = 1_500,
    private val cooldownMs: Long = 2_000,
) {
    private val recorder = AxisRecorder()
    private val window = ArrayDeque<Pair<Long, IntArray>>()
    private var blockedUntil = 0L

    fun onSample(timeMs: Long, orientation: List<Int>): HeadGesture? {
        val values = recorder.add(orientation)
        window.addLast(timeMs to values)
        while (window.isNotEmpty() && timeMs - window.first().first > windowMs) window.removeFirst()
        if (timeMs < blockedUntil) return null

        val nod = score(calibration.nodAxis, calibration.nodThreshold)
        val shake = score(calibration.shakeAxis, calibration.shakeThreshold)
        val gesture = when {
            nod >= 1f && nod > shake * DOMINANCE -> HeadGesture.NOD
            shake >= 1f && shake > nod * DOMINANCE -> HeadGesture.SHAKE
            else -> null
        }
        if (gesture != null) {
            blockedUntil = timeMs + cooldownMs
            window.clear()
        }
        return gesture
    }

    /** Размах в порогах, если было «туда и обратно»; иначе 0. */
    private fun score(axis: Int, threshold: Int): Float {
        if (threshold <= 0 || window.size < 3) return 0f
        val series = window.map { it.second[axis] }
        if (swings(series, threshold / 2) < 2) return 0f
        return (series.max() - series.min()).toFloat() / threshold
    }

    /**
     * Сколько раз сигнал прошёл не меньше [step] в одну сторону, считая и смену направления.
     * Кивок «вниз и обратно» даёт 2, просто повёрнутая голова — 1.
     */
    private fun swings(series: List<Int>, step: Int): Int {
        var count = 0
        var direction = 0 // 1 — растёт, −1 — падает, 0 — ещё не ясно
        val start = series.first()
        var extreme = start
        for (value in series) {
            when (direction) {
                0 -> if (abs(value - start) >= step) {
                    direction = if (value > start) 1 else -1
                    extreme = value
                    count++
                }
                1 -> if (value > extreme) {
                    extreme = value
                } else if (extreme - value >= step) {
                    direction = -1
                    extreme = value
                    count++
                }
                else -> if (value < extreme) {
                    extreme = value
                } else if (value - extreme >= step) {
                    direction = 1
                    extreme = value
                    count++
                }
            }
        }
        return count
    }

    private companion object {
        /** Ось жеста должна двигаться хотя бы в полтора раза сильнее другой (в порогах). */
        const val DOMINANCE = 1.5f
    }
}
