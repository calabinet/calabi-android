package net.calabi.app.rdp

import android.graphics.PointF
import android.graphics.RectF

/** Pure IME zoom/pan calculation kept separate from android.graphics for JVM tests. */
internal object RdpViewportMath {
    fun imeTargetZoom(
        previousPixelScale: Float,
        currentFit: Float,
        currentZoom: Float,
        originalSize: Boolean,
        minBoost: Float = 1.0f,
    ): Float {
        if (originalSize || previousPixelScale <= 0f || currentFit <= 0f) return currentZoom.coerceIn(1f, 5f)
        val needed = (previousPixelScale * minBoost) / currentFit
        return maxOf(currentZoom, needed).coerceIn(1f, 5f)
    }

    fun focusPan(
        remoteCoord: Float,
        remoteLength: Int,
        viewLength: Int,
        pixelScale: Float,
        anchorFraction: Float,
    ): Float {
        if (remoteLength <= 0 || viewLength <= 0 || pixelScale <= 0f) return 0f
        val scaledLength = remoteLength * pixelScale
        val maxPan = maxOf(0f, (scaledLength - viewLength) / 2f)
        val desiredLocal = viewLength * anchorFraction
        val baseLeft = (viewLength - scaledLength) / 2f
        return (desiredLocal - (baseLeft + remoteCoord * pixelScale)).coerceIn(-maxPan, maxPan)
    }
}

/** One transform for painting and input, including letterboxing, zoom and keyboard resize. */
internal class RdpViewport {
    var remoteWidth = 0; private set
    var remoteHeight = 0; private set
    private var width = 0
    private var height = 0
    var zoom = 1f; private set
    var originalSize = false; private set
    private var panX = 0f
    private var panY = 0f
    val bounds = RectF()
    private val fit: Float get() = if (originalSize) 1f else if (remoteWidth > 0 && remoteHeight > 0)
        minOf(width.toFloat() / remoteWidth, height.toFloat() / remoteHeight) else 0f

    var imeFocused = false; private set
    private var preImeZoom = 1f
    private var preImePanX = 0f
    private var preImePanY = 0f
    private var preImeOriginalSize = false
    private var preImePixelScale = 0f
    private var stableHeight = 0
    private var stablePixelScale = 0f

    fun configure(width: Int, height: Int, remoteWidth: Int, remoteHeight: Int) {
        val previousFit = fit
        val changedRemote = this.remoteWidth != remoteWidth || this.remoteHeight != remoteHeight
        val orientationFlipped = this.width > 0 && this.height > 0 && ((width > height) != (this.width > this.height))
        this.width = width; this.height = height
        this.remoteWidth = remoteWidth; this.remoteHeight = remoteHeight
        if (changedRemote) {
            zoom = 1f; panX = 0f; panY = 0f
            imeFocused = false
            stableHeight = 0; stablePixelScale = 0f
        } else if (previousFit > 0 && !imeFocused) {
            panX *= fit / previousFit; panY *= fit / previousFit
        }
        update()
        if (!imeFocused && (changedRemote || orientationFlipped || stableHeight == 0 || height >= stableHeight)) {
            stableHeight = height
            stablePixelScale = pixelScale
        }
    }

    /** Preserve readable text scale and keep the active cursor/input visible when the soft keyboard shrinks the view. */
    fun beginImeFocus(remoteX: Float, remoteY: Float, previousPixelScale: Float = 0f) {
        if (remoteWidth <= 0 || remoteHeight <= 0 || width <= 0 || height <= 0) return
        if (!imeFocused) {
            imeFocused = true
            preImeZoom = zoom
            preImePanX = panX
            preImePanY = panY
            preImeOriginalSize = originalSize
            preImePixelScale = maxOf(stablePixelScale, previousPixelScale, pixelScale)
        }
        zoom = RdpViewportMath.imeTargetZoom(preImePixelScale, fit, zoom, originalSize)
        update()
        val scale = pixelScale
        panX = RdpViewportMath.focusPan(remoteX, remoteWidth, width, scale, 0.5f)
        panY = RdpViewportMath.focusPan(remoteY, remoteHeight, height, scale, 0.62f)
        update()
    }

    fun endImeFocus() {
        if (!imeFocused) return
        imeFocused = false
        originalSize = preImeOriginalSize
        zoom = preImeZoom
        panX = preImePanX
        panY = preImePanY
        update()
        if (height >= stableHeight) {
            stableHeight = height
            stablePixelScale = pixelScale
        }
    }

    private fun update() {
        val w = remoteWidth * fit * zoom; val h = remoteHeight * fit * zoom
        val maxX = maxOf(0f, (w - width) / 2); val maxY = maxOf(0f, (h - height) / 2)
        panX = panX.coerceIn(-maxX, maxX); panY = panY.coerceIn(-maxY, maxY)
        val left = (width - w) / 2 + panX; val top = (height - h) / 2 + panY
        bounds.set(left, top, left + w, top + h)
    }

    fun scale(factor: Float, oldX: Float, oldY: Float, newX: Float = oldX, newY: Float = oldY) {
        if (!factor.isFinite() || factor <= 0 || bounds.width() <= 0 || bounds.height() <= 0) return
        val next = (zoom * factor).coerceIn(1f, 5f)
        val ratio = next / zoom
        panX = newX - (oldX - bounds.left) * ratio + bounds.width() * ratio / 2 - width / 2f
        panY = newY - (oldY - bounds.top) * ratio + bounds.height() * ratio / 2 - height / 2f
        zoom = next
        update()
        if (!imeFocused) stablePixelScale = pixelScale
    }

    fun scale(factor: Float) = scale(factor, width / 2f, height / 2f)
    fun pan(dx: Float, dy: Float) { panX += dx; panY += dy; update() }
    val pixelScale: Float get() = fit * zoom
    fun localX(x: Float) = bounds.left + x * pixelScale
    fun localY(y: Float) = bounds.top + y * pixelScale
    fun local(x: Float, y: Float) = PointF(localX(x), localY(y))
    fun reveal(x: Float, y: Float, margin: Float) {
        val p = local(x, y)
        val mx = minOf(margin, width / 4f); val my = minOf(margin, height / 4f)
        val dx = if (p.x < mx) mx - p.x else if (p.x > width - mx) width - mx - p.x else 0f
        val dy = if (p.y < my) my - p.y else if (p.y > height - my) height - my - p.y else 0f
        pan(dx, dy)
    }
    fun actualPixels() { imeFocused = false; originalSize = true; zoom = 1f; panX = 0f; panY = 0f; update(); stablePixelScale = pixelScale }
    fun reset() { imeFocused = false; originalSize = false; zoom = 1f; panX = 0f; panY = 0f; update(); stablePixelScale = pixelScale }
    fun remote(x: Float, y: Float, clamp: Boolean = false): PointF? {
        if (bounds.width() <= 0 || bounds.height() <= 0 || (!clamp && !bounds.contains(x, y))) return null
        return PointF(((x - bounds.left) * remoteWidth / bounds.width()).coerceIn(0f, (remoteWidth - 1).toFloat()),
            ((y - bounds.top) * remoteHeight / bounds.height()).coerceIn(0f, (remoteHeight - 1).toFloat()))
    }
}
