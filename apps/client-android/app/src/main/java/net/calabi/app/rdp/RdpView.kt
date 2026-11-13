package net.calabi.app.rdp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.InputDevice
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
        set(value) { if (keyboard.latch != value) keyboard.setLatch(value) }
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager
    private val inputListener = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = Unit
        override fun onInputDeviceChanged(id: Int) = Unit
        override fun onInputDeviceRemoved(id: Int) { suspendInput() }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        inputManager.registerInputDeviceListener(inputListener, android.os.Handler(android.os.Looper.getMainLooper()))
    }
    override fun onDetachedFromWindow() {
        inputManager.unregisterInputDeviceListener(inputListener); suspendInput(); super.onDetachedFromWindow()
    }
    private val gestures = RdpGestures(viewport, ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        48 * resources.displayMetrics.density, { flags, x, y ->
            if (flags and 0x8000 != 0) keyboard.pointerModifiers()
            session.mouse(flags, x, y)
            if (flags and 0x8000 == 0 && flags and 0x7000 != 0) keyboard.pointerReleased()
        }, { postInvalidateOnAnimation(); onZoomChanged?.invoke(viewport.zoom) },
        { onRightClickConsumed?.invoke() }, { onDragConsumed?.invoke() })
    var trackpadMode: Boolean
        get() = gestures.trackpad
        set(value) {
            if (value != gestures.trackpad) {
                gestures.cancel(); keyboard.releaseAll(); gestures.trackpad = value
                postInvalidateOnAnimation()
            }
            if (value) gestures.prepareCursor()
        }
    var dragMode: Boolean
        get() = gestures.dragArmed
        set(value) { gestures.dragArmed = value }
    var rightClickMode: Boolean
        get() = gestures.rightClick
        set(value) { gestures.rightClick = value }
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
        keyboard.onLatchChanged = { onModifiersChanged?.invoke(it) }
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density }
    private val cursor = Path()

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
        bitmap?.let { viewport.configure(w, h, it.width, it.height) }
    }

    fun frame(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width.toLong() * height > 1920L * 1080) return
        if (bitmap?.width != width || bitmap?.height != height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
        if (gestures.trackpad && gestures.prepareCursor()) {
            val x = viewport.localX(gestures.cursorX)
            val y = viewport.localY(gestures.cursorY)
            val size = 18 * resources.displayMetrics.density
            cursor.rewind()
            cursor.moveTo(x, y); cursor.lineTo(x, y + size)
            cursor.lineTo(x + size * 0.3f, y + size * 0.7f)
            cursor.lineTo(x + size * 0.65f, y + size * 0.7f); cursor.close()
            canvas.drawPath(cursor, cursorFill); canvas.drawPath(cursor, cursorStroke)
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

    fun suspendInput() { inputEpoch++; gestures.cancel(); releaseMouse(); keyboard.releaseAll(); edit.clear() }
    fun release() { suspendInput(); bitmap?.recycle(); bitmap = null; renderedFrame = false }
}
