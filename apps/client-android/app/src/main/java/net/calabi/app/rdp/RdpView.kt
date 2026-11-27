package net.calabi.app.rdp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** Native RDP canvas; rendering and every pointer share the same viewport transform. */
// Created only by RdpActivity with an authorized session; never inflated from XML.
@SuppressLint("ViewConstructor")
internal class RdpView(context: Context, private val session: RdpSession) : View(context) {
    private var bitmap: Bitmap? = null
    private var rejectedFrameSize = 0L
    private var renderedFrame = false
    internal val viewport = RdpViewport()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val edit = SpannableStringBuilder()
    private val keyboard = RdpKeyboard(session::key, session::text)
    var onZoomChanged: ((Float) -> Unit)? = null
    var onRightClickConsumed: (() -> Unit)? = null
    var onDragConsumed: (() -> Unit)? = null
    var onModifiersChanged: ((Int) -> Unit)? = null
    var modifiers: Int
        get() = keyboard.latch
        set(value) {
            if (keyboard.latch != value) keyboard.setLatch(value)
            gestures.hasModifiers = value != 0
        }
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager
    private val inputListener = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = Unit
        override fun onInputDeviceChanged(id: Int) = Unit
        override fun onInputDeviceRemoved(id: Int) { suspendInput() }
    }

    private var flingRunning = false
    private var lastFlingAt = 0L
    private val flingTick: Runnable = object : Runnable {
        override fun run() {
            if (!flingRunning || !interactive) { flingRunning = false; return }
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFlingAt == 0L) 16L else (now - lastFlingAt).coerceIn(1L, 48L)
            lastFlingAt = now
            if (gestures.stepFling(dt)) postOnAnimation(this) else flingRunning = false
        }
    }

    private var feedbackAt = 0L
    private var feedbackX = 0f
    private var feedbackY = 0f
    private var feedbackRight = false
    private val feedbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * resources.displayMetrics.density
    }

    private var imeVisible = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        inputManager.registerInputDeviceListener(inputListener, android.os.Handler(android.os.Looper.getMainLooper()))
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(flingTick)
        flingRunning = false
        inputManager.unregisterInputDeviceListener(inputListener); suspendInput(); super.onDetachedFromWindow()
    }
    private val gestures: RdpGestures = RdpGestures(
        viewport = viewport,
        slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        wheelStep = 16 * resources.displayMetrics.density,
        density = resources.displayMetrics.density,
        mouse = { flags, x, y ->
            if (flags and 0x8000 != 0) keyboard.pointerModifiers()
            session.mouse(flags, x, y)
            if (flags and 0x8000 == 0 && flags and 0x7000 != 0) keyboard.pointerReleased()
        },
        touchOut = { action, id, x, y -> session.touch(action, id, x, y) },
        changed = { postInvalidateOnAnimation(); onZoomChanged?.invoke(viewport.zoom) },
        rightConsumed = { onRightClickConsumed?.invoke() },
        dragConsumed = { onDragConsumed?.invoke() },
        scheduleLongPress = { delayMs, task -> postDelayed(task, delayMs) },
        cancelLongPress = { task -> removeCallbacks(task) },
        onTapFeedback = { x, y, right ->
            if (right) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            feedbackX = x; feedbackY = y; feedbackRight = right
            feedbackAt = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        },
        onFlingChanged = { active ->
            removeCallbacks(flingTick)
            flingRunning = active
            if (active) {
                lastFlingAt = SystemClock.uptimeMillis()
                postOnAnimation(flingTick)
            }
        },
    )
    var trackpadMode: Boolean
        get() = gestures.trackpad
        set(value) {
            if (value != gestures.trackpad) {
                gestures.cancel(); keyboard.releaseAll(); gestures.trackpad = value
                postInvalidateOnAnimation()
            }
            if (value) gestures.prepareCursor()
        }
    var nativeTouchMode: Boolean
        get() = gestures.nativeTouch
        set(value) {
            if (value != gestures.nativeTouch) {
                gestures.cancel()
                gestures.nativeTouch = value
            }
        }
    var dragMode: Boolean
        get() = gestures.dragArmed
        set(value) { gestures.dragArmed = value; postInvalidateOnAnimation() }
    var rightClickMode: Boolean
        get() = gestures.rightClick
        set(value) { gestures.rightClick = value; postInvalidateOnAnimation() }
    var panMode: Boolean
        get() = gestures.panMode
        set(value) { gestures.panMode = value }
    private val mouse = RdpMouse(viewport, session::mouse, keyboard::pointerModifiers, keyboard::pointerReleased,
        gestures::placeCursor, { requestFocus() }, gestures::wheel)
    private var inputEpoch = 0L
    var interactive = true
        set(value) { if (field != value) { field = value; postInvalidateOnAnimation() } }
    fun resumeInput() {
        interactive = true
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).restartInput(this)
    }
    init {
        isFocusable = true; isFocusableInTouchMode = true; setBackgroundColor(Color.BLACK)
        keyboard.onLatchChanged = {
            gestures.hasModifiers = it != 0
            onModifiersChanged?.invoke(it)
        }
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density }
    private val cursorHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density }
    private val cursor = Path()

    fun setImeVisible(visible: Boolean) {
        if (imeVisible == visible) return
        imeVisible = visible
        if (visible) {
            if (bitmap != null && gestures.prepareCursor()) {
                viewport.beginImeFocus(gestures.cursorX, gestures.cursorY)
                postInvalidateOnAnimation()
                onZoomChanged?.invoke(viewport.zoom)
            }
        } else if (viewport.imeFocused) {
            viewport.endImeFocus()
            postInvalidateOnAnimation()
            onZoomChanged?.invoke(viewport.zoom)
        }
    }

    fun shortcut(modifiers: IntArray, scan: Int) {
        gestures.cancel(); releaseMouse(); keyboard.releaseAll()
        session.shortcut(modifiers, scan); requestFocus()
    }
    fun press(scan: Int) { keyboard.press(scan) }

    fun zoomBy(factor: Float) { gestures.cancel(); viewport.scale(factor); postInvalidateOnAnimation(); onZoomChanged?.invoke(viewport.zoom) }
    fun fitScreen() { gestures.cancel(); viewport.reset(); postInvalidateOnAnimation(); onZoomChanged?.invoke(viewport.zoom) }
    fun actualPixels() { gestures.cancel(); viewport.actualPixels(); postInvalidateOnAnimation(); onZoomChanged?.invoke(viewport.zoom) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gestures.cancel()
        releaseMouse()
        keyboard.releaseAll()
        bitmap?.let {
            viewport.configure(w, h, it.width, it.height)
            if (imeVisible && gestures.prepareCursor()) {
                viewport.beginImeFocus(gestures.cursorX, gestures.cursorY)
                onZoomChanged?.invoke(viewport.zoom)
            } else if (!imeVisible && viewport.imeFocused) {
                viewport.endImeFocus()
                onZoomChanged?.invoke(viewport.zoom)
            }
        }
        refreshRemoteViewport()
    }

    fun refreshRemoteViewport() {
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(this)
        if (insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true) updateRemoteViewport(width, height)
    }

    @Suppress("DEPRECATION") // Display-specific logical size on Android 8–10 too.
    private fun updateRemoteViewport(w: Int, h: Int) {
        if (session.quality != RdpQuality.ADAPTIVE || w <= 0 || h <= 0) return
        val target = display
        if (target != null && target.displayId != Display.DEFAULT_DISPLAY) {
            val size = Point().also(target::getRealSize)
            if (size.x > 0 && size.y > 0) {
                val mode = target.mode
                val landscape = size.x >= size.y
                val physicalWidth = if (landscape) maxOf(mode.physicalWidth, mode.physicalHeight) else minOf(mode.physicalWidth, mode.physicalHeight)
                val physicalHeight = if (landscape) minOf(mode.physicalWidth, mode.physicalHeight) else maxOf(mode.physicalWidth, mode.physicalHeight)
                session.viewport((w.toLong() * physicalWidth / size.x).toInt(), (h.toLong() * physicalHeight / size.y).toInt())
                return
            }
        }
        session.viewport(w, h)
    }

    fun frame(width: Int, height: Int) {
        if (width !in 1..3840 || height !in 1..3840 || width.toLong() * height > 3840L * 2160) return
        if (bitmap?.width != width || bitmap?.height != height) {
            val size = (width.toLong() shl 32) or height.toLong()
            if (size == rejectedFrameSize) return
            gestures.cancel()
            releaseMouse()
            bitmap?.recycle()
            bitmap = try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                rejectedFrameSize = size
                if (session.quality == RdpQuality.ADAPTIVE && width.toLong() * height > 1920L * 1080) {
                    session.viewport(if (width >= height) 1920 else 1080, if (width >= height) 1080 else 1920)
                } else {
                    session.close(2)
                }
                null
            }
            if (bitmap == null) return
            renderedFrame = false
            viewport.configure(this.width, this.height, width, height)
            onZoomChanged?.invoke(viewport.zoom)
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!interactive) return
        val image = bitmap ?: return
        if (session.blit(image)) renderedFrame = true
        canvas.drawBitmap(image, null, viewport.bounds, paint)
        val density = resources.displayMetrics.density
        if (gestures.trackpad && gestures.prepareCursor()) {
            val x = viewport.localX(gestures.cursorX)
            val y = viewport.localY(gestures.cursorY)
            if (gestures.dragArmed || gestures.isPressed || gestures.rightClick) {
                cursorHalo.color = if (gestures.rightClick) Color.argb(210, 245, 158, 11) else Color.argb(210, 37, 99, 235)
                canvas.drawCircle(x, y, 10f * density, cursorHalo)
            }
            val size = 18 * density
            cursor.rewind()
            cursor.moveTo(x, y); cursor.lineTo(x, y + size)
            cursor.lineTo(x + size * 0.3f, y + size * 0.7f)
            cursor.lineTo(x + size * 0.65f, y + size * 0.7f); cursor.close()
            canvas.drawPath(cursor, cursorFill); canvas.drawPath(cursor, cursorStroke)
        }
        val elapsed = SystemClock.uptimeMillis() - feedbackAt
        if (feedbackAt != 0L && elapsed in 0..240) {
            val t = elapsed / 240f
            val radius = (8f + 18f * t) * density
            val alpha = ((1f - t) * 210f).toInt().coerceIn(0, 255)
            feedbackPaint.color = if (feedbackRight) Color.argb(alpha, 245, 158, 11) else Color.argb(alpha, 255, 255, 255)
            canvas.drawCircle(feedbackX, feedbackY, radius, feedbackPaint)
            postInvalidateOnAnimation()
        }
    }

    /** Copy only the remote framebuffer, excluding phone chrome, IME and cursor overlays. Main thread only. */
    fun thumbnail(): Bitmap? {
        val image = bitmap?.takeIf { renderedFrame && interactive } ?: return null
        val scale = (640f / maxOf(image.width, image.height)).coerceAtMost(1f)
        val scaled = Bitmap.createScaledBitmap(image, (image.width * scale).toInt().coerceAtLeast(1),
            (image.height * scale).toInt().coerceAtLeast(1), true)
        return if (scaled === image) image.copy(Bitmap.Config.ARGB_8888, false) else scaled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || !interactive) return true
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return physicalMouse(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
        val handled = gestures.touch(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return handled
    }

    private fun releaseMouse() {
        mouse.release()
    }

    private fun physicalMouse(event: MotionEvent): Boolean {
        return mouse.event(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        if (!interactive) true else if (event.isFromSource(InputDevice.SOURCE_MOUSE)) physicalMouse(event) else super.onGenericMotionEvent(event)

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) { gestures.cancel(); releaseMouse(); keyboard.releaseAll() }
        super.onWindowFocusChanged(hasWindowFocus)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        val epoch = inputEpoch
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return object : BaseInputConnection(this, true) {
            override fun getEditable(): Editable = edit
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!interactive || epoch != inputEpoch) return true
                keyboard.commit(text?.toString().orEmpty()); edit.clear(); return true
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                if (!interactive || epoch != inputEpoch) true else super.setComposingText(text, newCursorPosition)
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (!interactive || epoch != inputEpoch) return true
                if (getComposingSpanStart(edit) >= 0) return super.deleteSurroundingText(beforeLength, afterLength)
                repeat(beforeLength.coerceIn(0, 64)) { session.press(0x0e) }
                repeat(afterLength.coerceIn(0, 64)) { session.press(0x153) }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean =
                if (!interactive || epoch != inputEpoch) true else this@RdpView.dispatchKeyEvent(event)
            override fun performContextMenuAction(id: Int): Boolean {
                if (!interactive || epoch != inputEpoch) return true
                val scan = when (id) {
                    android.R.id.selectAll -> 0x1e; android.R.id.copy -> 0x2e
                    android.R.id.paste -> 0x2f; android.R.id.cut -> 0x2d
                    else -> return super.performContextMenuAction(id)
                }
                shortcut(intArrayOf(0x1d), scan); return true
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!interactive && event.keyCode != KeyEvent.KEYCODE_BACK) return true
        if (keyboard.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        // Hardware shortcuts must reach Windows before the Android IME treats our editor as local text.
        val control = RdpScan.modifier(keyCode) != 0 || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
            (event.unicodeChar == 0 && RdpScan.key(keyCode) != 0 && BaseInputConnection.getComposingSpanStart(edit) < 0)
        if (event.isFromSource(InputDevice.SOURCE_KEYBOARD) && control) {
            if (!interactive) return true
            if (keyboard.dispatch(event)) return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    fun suspendInput() {
        removeCallbacks(flingTick); flingRunning = false
        inputEpoch++; gestures.cancel(); releaseMouse(); keyboard.releaseAll(); edit.clear()
    }
    fun release() { suspendInput(); bitmap?.recycle(); bitmap = null; renderedFrame = false }
}
