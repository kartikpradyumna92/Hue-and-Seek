package com.colorwalk.app.ui.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.colorwalk.app.domain.ColorValidator
import com.colorwalk.app.domain.WalkColor
import kotlinx.coroutines.channels.SendChannel
import java.nio.ByteBuffer

/**
 * Real-time "how much of the frame is today's color" producer.
 *
 * Non-blocking producer/consumer pipeline:
 *  - **Producer (this analyzer, camera executor thread):** downsamples each
 *    YUV_420_888 frame onto a fixed 64×48 sample grid, converts to ARGB, and runs the
 *    allocation-free [ColorValidator.liveTargetShare]. 3 072 samples keeps a full
 *    pass well under a 60 Hz half-frame budget (~8 ms) on any hardware that runs
 *    this app, so the analyzer never becomes the viewfinder's bottleneck.
 *  - **Frame dropping:** the ImageAnalysis use case is bound with
 *    [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST], so if a pass ever does run long,
 *    CameraX drops the intermediate frames instead of queueing them — the preview
 *    can't lag behind the analyzer by design.
 *  - **Consumer decoupling:** results go through a CONFLATED [SendChannel]; if the
 *    UI collector is busy composing, stale shares are overwritten rather than
 *    buffered, so the meter always renders the newest value and never replays a
 *    backlog.
 *
 * Zero-steady-state-allocation: the sample grid and HSV scratch buffers are
 * preallocated once; per frame only the boxed Float crossing the channel is
 * allocated. Frame rotation is ignored — the spatial weight in liveTargetShare is
 * radially symmetric, so a 90° rotation barely moves the share.
 */
class LiveColorAnalyzer(
    private val shares: SendChannel<Float>,
    private val targetProvider: () -> WalkColor
) : ImageAnalysis.Analyzer {

    private val pixels = IntArray(SAMPLE_W * SAMPLE_H)
    private val hsv = FloatArray(3)
    private var lastSentShare = -1f
    private var loggedFailure = false

    override fun analyze(image: ImageProxy) {
        try {
            downsampleYuvToArgb(image)
            val share = ColorValidator.liveTargetShare(pixels, SAMPLE_W, SAMPLE_H, targetProvider(), hsv)
            // Change gate at the PRODUCER: a steady scene sends nothing — no Float
            // boxing, no channel traffic, no UI wakeups. Only frames where the meter
            // would visibly move (≥0.5%) cross the thread boundary, which is the last
            // allocation left in this loop.
            if (kotlin.math.abs(share - lastSentShare) >= 0.005f) {
                lastSentShare = share
                shares.trySend(share) // CONFLATED: overwrites any unconsumed value, never blocks
            }
        } catch (e: Exception) {
            // A torn frame (device rotation, camera rebind) is worthless — skip it.
            // Logged once (not per-frame — this runs at up to 60fps) so a systematic
            // failure is visible in logcat instead of silently freezing the meter.
            if (!loggedFailure) {
                loggedFailure = true
                Log.w("LiveColorAnalyzer", "Frame analysis failed, live meter will not update", e)
            }
        } finally {
            image.close() // MUST close or CameraX stops delivering frames
        }
    }

    /** Samples the Y/U/V planes on the fixed grid and packs ARGB into [pixels]. */
    private fun downsampleYuvToArgb(image: ImageProxy) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        // Semi-planar YUV_420_888 (pixelStride=2, U/V interleaved in one backing
        // array) is common, and on many devices each chroma plane's ByteBuffer.limit
        // is exactly one byte short of what naive rowStride/pixelStride arithmetic
        // computes for the very last sampled row/column — a real, well-documented
        // Android camera quirk. A fixed sample grid hits that same boundary index on
        // EVERY frame, so an unguarded get() there doesn't just skip one bad frame,
        // it throws on every frame from then on, and the meter never receives a
        // single value. Clamping to each buffer's actual limit trades one wrong
        // sample at the frame's extreme edge (invisible in a 3 072-sample average)
        // for never dropping the whole frame.
        val yLimit = yBuf.limit()
        val uLimit = uBuf.limit()
        val vLimit = vBuf.limit()
        val w = image.width
        val h = image.height

        var i = 0
        for (sy in 0 until SAMPLE_H) {
            val y = sy * h / SAMPLE_H
            val uvY = y shr 1
            for (sx in 0 until SAMPLE_W) {
                val x = sx * w / SAMPLE_W
                val uvX = x shr 1
                val lum = yBuf.getClamped(y * yPlane.rowStride + x * yPlane.pixelStride, yLimit)
                val u = uBuf.getClamped(uvY * uPlane.rowStride + uvX * uPlane.pixelStride, uLimit)
                val v = vBuf.getClamped(uvY * vPlane.rowStride + uvX * vPlane.pixelStride, vLimit)
                pixels[i++] = YuvMath.argbOf(lum, u, v)
            }
        }
    }

    private fun ByteBuffer.getClamped(index: Int, limit: Int): Int =
        get(index.coerceIn(0, limit - 1)).toInt() and 0xFF

    companion object {
        const val SAMPLE_W = 64
        const val SAMPLE_H = 48
    }
}

/**
 * BT.601 full-range YUV→ARGB, pure Kotlin (JVM-testable). Fixed-point integer math —
 * no float ops, no allocation.
 */
internal object YuvMath {
    fun argbOf(y: Int, u: Int, v: Int): Int {
        val c = y
        val d = u - 128
        val e = v - 128
        // 1.402 ≈ 359/256, 0.344 ≈ 88/256, 0.714 ≈ 183/256, 1.772 ≈ 454/256
        val r = (c + ((359 * e) shr 8)).coerceIn(0, 255)
        val g = (c - ((88 * d + 183 * e) shr 8)).coerceIn(0, 255)
        val b = (c + ((454 * d) shr 8)).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
