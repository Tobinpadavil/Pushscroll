package com.teserakt.pushscroll

/**
 * Turns the head's position into pushup reps.
 *
 * Two signals come from the front camera every frame:
 *  - y: vertical position of the head in the frame (0 = top, 1 = bottom)
 *  - s: head size (face width / frame width) — grows as you go down toward the phone
 *
 * Whichever signal is moving more over the last few seconds is used, so it works whether
 * the phone lies flat under your face or leans up in front of you. A rep counts when the
 * head goes clearly "down" (toward the phone) and then comes back clearly "up".
 */
class RepCounter {
    private class Sample(val t: Long, val y: Float, val s: Float?)

    private val buf = ArrayDeque<Sample>()
    private var ey = Float.NaN
    private var es = Float.NaN
    private var down = false
    private var downAt = 0L
    private var lastRep = 0L
    private var useScale = false

    /** 0 = top of the pushup, 1 = bottom. For the on-screen bar. */
    var level = 0f
        private set

    /** 0..100, higher = smaller movements count. */
    var sensitivity = 50

    fun reset() {
        buf.clear()
        ey = Float.NaN
        es = Float.NaN
        down = false
        level = 0f
    }

    fun update(t: Long, yRaw: Float, sRaw: Float?): Boolean {
        ey = if (ey.isNaN()) yRaw else ey + 0.45f * (yRaw - ey)
        es = when {
            sRaw == null -> Float.NaN
            es.isNaN() -> sRaw
            else -> es + 0.45f * (sRaw - es)
        }
        buf.addLast(Sample(t, ey, if (es.isNaN()) null else es))
        while (buf.isNotEmpty() && t - buf.first().t > WINDOW_MS) buf.removeFirst()
        if (buf.size < 8) return false

        // Sensitivity 0 -> needs 1.6x the default movement, 100 -> 0.5x
        val f = 1.6f - 1.1f * (sensitivity.coerceIn(0, 100) / 100f)

        val minY = buf.minOf { it.y }
        val maxY = buf.maxOf { it.y }
        val scoreY = (maxY - minY) / (MIN_Y_MOVE * f)

        var scoreS = 0f
        var minS = 0f
        var maxS = 0f
        val ss = buf.mapNotNull { it.s }
        if (!es.isNaN() && ss.size >= buf.size / 2) {
            minS = ss.min()
            maxS = ss.max()
            val mean = ss.average().toFloat()
            if (mean > 0f) scoreS = ((maxS - minS) / mean) / (MIN_SIZE_CHANGE * f)
        }

        useScale = if (es.isNaN()) false
        else if (useScale) scoreS * 1.3f >= scoreY
        else scoreS > scoreY * 1.3f

        val v: Float
        val mn: Float
        val mx: Float
        val score: Float
        if (useScale) { v = es; mn = minS; mx = maxS; score = scoreS }
        else { v = ey; mn = minY; mx = maxY; score = scoreY }

        if (score < 1f || mx - mn <= 0f) {
            level = 0f
            return false
        }

        // Bigger y (lower in frame) or bigger head = closer to the floor = "down"
        val d = ((v - mn) / (mx - mn)).coerceIn(0f, 1f)
        level = d

        if (!down && d > 0.72f) {
            down = true
            downAt = t
        } else if (down && d < 0.30f) {
            down = false
            if (t - downAt >= 200 && t - lastRep >= 500) {
                lastRep = t
                return true
            }
        }
        return false
    }

    companion object {
        private const val WINDOW_MS = 5000L
        private const val MIN_Y_MOVE = 0.06f       // 6% of frame height
        private const val MIN_SIZE_CHANGE = 0.14f  // 14% change in head size
    }
}
