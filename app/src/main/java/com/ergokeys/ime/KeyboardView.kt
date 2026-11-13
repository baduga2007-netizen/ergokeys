package com.ergokeys.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.min

class KeyboardView(
    context: Context,
    private val prefs: ErgoPrefs,
    private val listener: Listener
) : View(context) {

    interface Listener {
        fun onChar(text: String)
        fun onBackspace()
        fun onDeleteWord()
        fun onSpace()
        fun onEnter()
        fun onMoveCursor(delta: Int)
        fun onLanguage()
        fun onClipboard()
        fun onSuggestion(word: String)
    }

    enum class Mode { LETTERS, SYMBOLS }
    enum class ShiftState { OFF, ONCE, LOCKED }

    private class PlacedKey(
        val spec: KeySpec,
        val rect: RectF,
        val hit: RectF
    )

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var palette = Palette.create(context)
    private var layout: LayoutSpec = KeyboardLayouts.letters(prefs.language)
    private var keys = ArrayList<PlacedKey>()
    private var suggestionRects = ArrayList<RectF>()

    var mode: Mode = Mode.LETTERS
        private set
    var language: String = prefs.language
        private set
    var shift: ShiftState = ShiftState.OFF
        private set

    private var suggestions: List<String> = emptyList()
    private var pressed: PlacedKey? = null
    private var pressedSuggestion = -1

    /** Отступ снизу под системную панель навигации, задаётся сервисом. */
    private var bottomInset = 0

    private var downX = 0f
    private var startX = 0f
    private var downY = 0f
    private var moved = false
    private var spaceDragged = false
    private var wordSwipe = false
    private var cursorDrag = 0f
    private var cursorSteps = 0
    private var pendingText: String? = null
    private var backspaceRepeated = false
    private var lastShiftTap = 0L

    private val slop = 12f * density
    private val cursorStep = 18f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bgPath = Path()

    private val deleteRunnable = object : Runnable {
        override fun run() {
            if (pressed?.spec?.action != KeyAction.BACKSPACE) return
            listener.onBackspace()
            backspaceRepeated = true
            handler.postDelayed(this, 55)
        }
    }

    private val altRunnable = Runnable {
        val p = pressed
        val alt = p?.spec?.alt
        if (p != null && alt != null && !moved && mode == Mode.LETTERS) {
            pendingText = null
            listener.onChar(applyShift(alt))
            tapHaptic()
            invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    // ---------------------------------------------------------------- API

    fun setBottomInset(px: Int) {
        val safe = if (px < 0) 0 else px
        if (safe == bottomInset) return
        bottomInset = safe
        rebuildGeometry()
        invalidate()
    }

    /** Высота, реально доступная клавишам (без системной панели). */
    fun usableHeight(): Int = (height - bottomInset).coerceAtLeast(0)

    fun setLanguage(lang: String) {
        language = lang
        rebuildLayout()
    }

    fun setMode(newMode: Mode) {
        mode = newMode
        rebuildLayout()
    }

    fun setSuggestions(list: List<String>) {
        suggestions = list.take(3)
        invalidate()
    }

    fun setShift(state: ShiftState) {
        shift = state
        invalidate()
    }

    fun refreshPreferences() {
        palette = Palette.create(context)
        rebuildLayout()
    }

    fun cancelPress() {
        pressed = null
        pressedSuggestion = -1
        pendingText = null
        cursorSteps = 0
        handler.removeCallbacks(deleteRunnable)
        handler.removeCallbacks(altRunnable)
        invalidate()
    }

    data class PanelColors(
        val background: Int,
        val text: Int,
        val container: Int,
        val accentText: Int
    )

    fun panelColors() = PanelColors(
        background = palette.background,
        text = palette.text,
        container = palette.key,
        accentText = palette.accent
    )

    private fun rebuildLayout() {
        layout = when (mode) {
            Mode.LETTERS -> KeyboardLayouts.letters(language)
            Mode.SYMBOLS -> KeyboardLayouts.symbols(language)
        }
        rebuildGeometry()
        invalidate()
    }

    // ---------------------------------------------------------- geometry

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry()
    }

    private fun contentBounds(): Pair<Float, Float> {
        val usable = if (prefs.oneHand == 0) width.toFloat() else width * 0.86f
        val left = if (prefs.oneHand == 2) width - usable else 0f
        return left to usable
    }

    private fun rebuildGeometry() {
        if (width == 0 || height == 0) return
        keys = ArrayList()
        suggestionRects = ArrayList()

        val (contentLeft, contentWidth) = contentBounds()
        val pad = dp(4f)
        val top = if (prefs.suggestions) dp(42f) else dp(3f)
        val rowCount = layout.rows.size
        val usable = (height - bottomInset).toFloat()
        val rowHeight = ((usable - top - dp(5f)) / rowCount).coerceAtLeast(dp(26f))
        val cell = (contentWidth - pad * 2f) / KeyboardLayouts.columns()
        val inset = dp(2.2f)
        val verticalInset = dp(3.2f)
        val hitScale = prefs.hitboxPercent / 100f

        layout.rows.forEachIndexed { rowIndex, row ->
            val sum = row.keys.sumOf { it.weight.toDouble() }.toFloat()
            val startCol = (KeyboardLayouts.columns() - sum) / 2f
            var col = startCol
            val rowTop = top + rowIndex * rowHeight
            row.keys.forEach { spec ->
                val x = contentLeft + pad + col * cell
                val w = spec.weight * cell
                val rect = RectF(
                    x + inset,
                    rowTop + verticalInset,
                    x + w - inset,
                    rowTop + rowHeight - verticalInset
                )
                val growX = rect.width() * (hitScale - 1f) / 2f
                val growY = rect.height() * (hitScale - 1f) / 2f
                val hit = RectF(
                    rect.left - growX,
                    rect.top - growY,
                    rect.right + growX,
                    rect.bottom + growY
                )
                keys.add(PlacedKey(spec, rect, hit))
                col += spec.weight
            }
        }

        if (prefs.suggestions) {
            val slot = contentWidth / 3f
            for (i in 0 until 3) {
                suggestionRects.add(
                    RectF(
                        contentLeft + i * slot + dp(2f),
                        dp(4f),
                        contentLeft + (i + 1) * slot - dp(2f),
                        dp(38f)
                    )
                )
            }
        }
        pressed = null
        pressedSuggestion = -1
    }

    // ------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                startX = event.x
                downY = event.y
                moved = false
                spaceDragged = false
                wordSwipe = false
                cursorDrag = 0f
                cursorSteps = 0
                pendingText = null
                backspaceRepeated = false

                val suggestion = suggestionAt(event.x, event.y)
                if (suggestion >= 0) {
                    pressedSuggestion = suggestion
                    invalidate()
                    return true
                }

                val key = keyAt(event.x, event.y) ?: return true
                pressed = key
                handleKeyDown(key)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pressed == null) return true
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > slop || abs(dy) > slop) moved = true

                when (pressed?.spec?.action) {
                    KeyAction.BACKSPACE -> {
                        if (dx < -dp(24f)) {
                            wordSwipe = true
                            handler.removeCallbacks(deleteRunnable)
                            handler.removeCallbacks(altRunnable)
                        }
                    }

                    KeyAction.SPACE -> {
                        handler.removeCallbacks(altRunnable)
                        cursorDrag += event.x - downX
                        downX = event.x
                        var steps = 0
                        while (cursorDrag > cursorStep) {
                            cursorDrag -= cursorStep
                            steps++
                            cursorSteps++
                        }
                        while (cursorDrag < -cursorStep) {
                            cursorDrag += cursorStep
                            steps--
                            cursorSteps--
                        }
                        if (steps != 0) {
                            spaceDragged = true
                            listener.onMoveCursor(steps)
                        }
                        if (prefs.cursorHint && cursorSteps != 0) invalidate()
                    }

                    else -> {
                        if (moved) handler.removeCallbacks(altRunnable)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (pressedSuggestion >= 0) {
                    val index = pressedSuggestion
                    pressedSuggestion = -1
                    suggestions.getOrNull(index)?.let { listener.onSuggestion(it) }
                    invalidate()
                    return true
                }

                val key = pressed
                pressed = null
                handler.removeCallbacks(deleteRunnable)
                handler.removeCallbacks(altRunnable)
                val dragged = spaceDragged

                val pending = pendingText
                pendingText = null
                if (pending != null) {
                    listener.onChar(pending)
                    tapHaptic()
                }

                if (key != null) {
                    when (key.spec.action) {
                        KeyAction.BACKSPACE -> {
                            if (wordSwipe) {
                                strongHaptic()
                                listener.onDeleteWord()
                            } else if (!backspaceRepeated) {
                                listener.onBackspace()
                                tapHaptic()
                            }
                        }

                        KeyAction.SPACE -> {
                            if (!dragged && abs(event.x - startX) < dp(24f)) {
                                listener.onSpace()
                            }
                        }

                        else -> Unit
                    }
                }
                cursorSteps = 0
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                pressedSuggestion = -1
                pendingText = null
                handler.removeCallbacks(deleteRunnable)
                handler.removeCallbacks(altRunnable)
                cursorSteps = 0
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleKeyDown(key: PlacedKey) {
        when (key.spec.action) {
            KeyAction.CHAR -> {
                val text = applyShift(key.spec.output ?: key.spec.label)
                if (!key.spec.alt.isNullOrEmpty() && mode == Mode.LETTERS) {
                    // Ждём: одиночный тап или долгое нажатие с «ё»/«ъ».
                    pendingText = text
                    handler.postDelayed(altRunnable, 420)
                } else {
                    listener.onChar(text)
                    tapHaptic()
                }
                if (shift == ShiftState.ONCE) shift = ShiftState.OFF
            }

            KeyAction.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == ShiftState.OFF -> ShiftState.ONCE
                    shift == ShiftState.ONCE && now - lastShiftTap < 340 -> ShiftState.LOCKED
                    else -> ShiftState.OFF
                }
                lastShiftTap = if (shift == ShiftState.ONCE) now else 0L
                tapHaptic()
            }

            KeyAction.BACKSPACE -> {
                handler.postDelayed(deleteRunnable, 80)
            }

            KeyAction.SPACE -> Unit

            KeyAction.ENTER -> {
                listener.onEnter()
                tapHaptic()
            }

            KeyAction.LANGUAGE -> {
                listener.onLanguage()
                tapHaptic()
            }

            KeyAction.SYMBOLS -> {
                setMode(Mode.SYMBOLS)
                tapHaptic()
            }

            KeyAction.LETTERS -> {
                setMode(Mode.LETTERS)
                tapHaptic()
            }

            KeyAction.CLIPBOARD -> {
                listener.onClipboard()
                tapHaptic()
            }

            KeyAction.NONE -> Unit
        }
    }

    private fun applyShift(text: String): String {
        if (shift == ShiftState.OFF) return text
        return text.uppercase()
    }

    private fun suggestionAt(x: Float, y: Float): Int {
        suggestionRects.forEachIndexed { i, rect ->
            if (i < suggestions.size && rect.contains(x, y)) return i
        }
        return -1
    }

    private fun keyAt(x: Float, y: Float): PlacedKey? {
        // 1) Точное попадание в видимую клавишу — она главнее любых хитбоксов.
        var inside: PlacedKey? = null
        var insideScore = Float.MAX_VALUE
        // 2) Иначе — ближайшая клавиша с учётом расширенного хитбокса.
        var near: PlacedKey? = null
        var nearDist = Float.MAX_VALUE
        var nearScore = Float.MAX_VALUE

        val grow = (prefs.hitboxPercent / 100f - 1f) / 2f
        for (key in keys) {
            val r = key.rect
            val cx = r.centerX()
            val cy = r.centerY()
            val center = (x - cx) * (x - cx) + (y - cy) * (y - cy)

            if (r.contains(x, y)) {
                if (center < insideScore) {
                    insideScore = center
                    inside = key
                }
                continue
            }

            val left = r.left - r.width() * grow
            val top = r.top - r.height() * grow
            val right = r.right + r.width() * grow
            val bottom = r.bottom + r.height() * grow
            val dx = when {
                x < left -> left - x
                x > right -> x - right
                else -> 0f
            }
            val dy = when {
                y < top -> top - y
                y > bottom -> y - bottom
                else -> 0f
            }
            val dist = dx * dx + dy * dy
            val score = dist * 1_000_000f + center
            if (dist < nearDist || (dist == nearDist && score < nearScore)) {
                nearDist = dist
                nearScore = score
                near = key
            }
        }

        inside?.let { return it }
        val limit = dp(30f)
        return if (nearDist <= limit * limit) near else null
    }

    private fun tapHaptic() {
        if (!prefs.haptics) return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun strongHaptic() {
        if (!prefs.haptics) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBackground(canvas)

        val (contentLeft, contentWidth) = contentBounds()
        if (prefs.suggestions) drawSuggestions(canvas, contentLeft, contentWidth)

        keys.forEach { key ->
            if (key === pressed) return@forEach
            drawKey(canvas, key, false)
        }
        pressed?.let { drawKey(canvas, it, true) }

        if (prefs.cursorHint && cursorSteps != 0) {
            drawCursorHint(canvas)
        }
    }

    private fun drawBackground(canvas: Canvas) {
        val r = dp(24f)
        bgPath.reset()
        bgPath.moveTo(0f, height.toFloat())
        bgPath.lineTo(0f, r)
        bgPath.quadTo(0f, 0f, r, 0f)
        bgPath.lineTo(width - r, 0f)
        bgPath.quadTo(width.toFloat(), 0f, width.toFloat(), r)
        bgPath.lineTo(width.toFloat(), height.toFloat())
        bgPath.close()
        paint.style = Paint.Style.FILL
        paint.color = palette.background
        canvas.drawPath(bgPath, paint)
    }

    private fun drawSuggestions(canvas: Canvas, left: Float, width: Float) {
        if (suggestions.isEmpty()) return
        val slot = width / 3f
        suggestions.forEachIndexed { i, word ->
            val rect = RectF(left + i * slot + dp(3f), dp(4f), left + (i + 1) * slot - dp(3f), dp(38f))
            if (i == 0) {
                paint.color = palette.accentContainer
                canvas.drawRoundRect(rect, dp(14f), dp(14f), paint)
            }
            textPaint.color = if (i == 0) palette.onAccentContainer else palette.text
            textPaint.textSize = sp(15f)
            textPaint.typeface = if (i == 0) {
                Typeface.create("sans-serif-medium", Typeface.BOLD)
            } else {
                Typeface.create("sans-serif", Typeface.NORMAL)
            }
            textPaint.textAlign = Paint.Align.CENTER
            val fm = textPaint.fontMetrics
            val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
            val label = if (word.length > 16) word.take(15) + "…" else word
            canvas.drawText(label, rect.centerX(), baseline, textPaint)
        }
    }

    private fun drawKey(canvas: Canvas, key: PlacedKey, isPressed: Boolean) {
        val rect = key.rect
        val spec = key.spec
        val radius = min(dp(15f), rect.height() / 2.8f)
        val fill = when {
            isPressed -> palette.pressed
            spec.kind == KeyKind.ACCENT -> palette.accent
            spec.kind == KeyKind.SPACE -> palette.key
            spec.kind == KeyKind.MOD -> palette.modifier
            spec.action == KeyAction.SHIFT && shift != ShiftState.OFF -> palette.accent
            else -> palette.key
        }
        paint.color = fill
        canvas.drawRoundRect(rect, radius, radius, paint)

        val onAccent = spec.kind == KeyKind.ACCENT ||
            (spec.action == KeyAction.SHIFT && shift != ShiftState.OFF)
        val labelColor = if (onAccent) palette.onAccent else palette.text
        textPaint.color = labelColor
        textPaint.textAlign = Paint.Align.CENTER

        val label = when {
            spec.action == KeyAction.SHIFT && shift == ShiftState.LOCKED -> "⇧⇧"
            spec.action == KeyAction.SHIFT && shift == ShiftState.ONCE -> "⇧"
            spec.kind == KeyKind.SPACE -> ""
            else -> spec.label
        }

        if (spec.kind == KeyKind.SPACE) {
            drawSpaceDots(canvas, rect)
        } else {
            textPaint.textSize = when {
                spec.kind == KeyKind.MOD || spec.kind == KeyKind.ACCENT -> sp(13f)
                spec.label.length == 1 -> sp(21f)
                else -> sp(15f)
            }
            textPaint.typeface = if (spec.kind == KeyKind.CHAR) {
                Typeface.create("sans-serif", Typeface.NORMAL)
            } else {
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val fm = textPaint.fontMetrics
            canvas.drawText(label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
        }

        if (isPressed && spec.kind == KeyKind.CHAR) {
            drawPreview(canvas, key)
        }
    }

    private fun drawSpaceDots(canvas: Canvas, rect: RectF) {
        paint.color = if (prefs.oneHand == 0) palette.subtle else palette.accent
        val r = dp(1.8f)
        val y = rect.centerY()
        val gap = dp(9f)
        for (i in -1..1) {
            canvas.drawCircle(rect.centerX() + i * gap, y, r, paint)
        }
    }

    private fun drawPreview(canvas: Canvas, key: PlacedKey) {
        val size = dp(48f)
        val gap = dp(8f)
        val cx = key.rect.centerX().coerceIn(size / 2f + dp(2f), width - size / 2f - dp(2f))
        val top = (key.rect.top - gap - size).coerceAtLeast(dp(2f))
        val rect = RectF(cx - size / 2f, top, cx + size / 2f, top + size)
        paint.color = palette.preview
        canvas.drawRoundRect(rect, dp(16f), dp(16f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = palette.accent
        canvas.drawRoundRect(rect, dp(16f), dp(16f), paint)
        paint.style = Paint.Style.FILL

        textPaint.color = palette.onPreview
        textPaint.textSize = sp(24f)
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textAlign = Paint.Align.CENTER
        val label = applyShift(key.spec.output ?: key.spec.label)
        val fm = textPaint.fontMetrics
        canvas.drawText(label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    private fun drawCursorHint(canvas: Canvas) {
        val label = (if (cursorSteps > 0) "→" else "←") + " " + abs(cursorSteps)
        textPaint.textSize = sp(14f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val w = dp(74f)
        val h = dp(30f)
        val cx = contentBounds().first + contentBounds().second / 2f
        val rect = RectF(cx - w / 2f, dp(42f), cx + w / 2f, dp(42f) + h)
        paint.color = palette.preview
        canvas.drawRoundRect(rect, dp(15f), dp(15f), paint)
        textPaint.color = palette.onPreview
        val fm = textPaint.fontMetrics
        canvas.drawText(label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float) = value * density
    private fun sp(value: Float) = value * resources.displayMetrics.scaledDensity

    // ------------------------------------------------------------ palette

    private data class Palette(
        val background: Int,
        val key: Int,
        val modifier: Int,
        val pressed: Int,
        val accent: Int,
        val accentContainer: Int,
        val onAccent: Int,
        val onAccentContainer: Int,
        val text: Int,
        val subtle: Int,
        val preview: Int,
        val onPreview: Int
    ) {
        companion object {
            fun create(context: Context): Palette {
                val night = (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                val seed = runCatching {
                    context.getColor(android.R.color.system_accent1_500)
                }.getOrElse { 0xFF6750A4.toInt() }

                fun mix(color: Int, target: Int, amount: Float) =
                    ColorUtils.blendARGB(color, target, amount)

                return if (night) {
                    val bg = mix(seed, 0xFF100E13.toInt(), 0.90f)
                    val key = mix(seed, 0xFF1D1B20.toInt(), 0.84f)
                    val mod = mix(seed, 0xFF2B2830.toInt(), 0.72f)
                    val accent = mix(seed, 0xFFFFFFFF.toInt(), 0.28f)
                    val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.45) {
                        0xFF1A1118.toInt()
                    } else Color.WHITE
                    Palette(
                        background = bg,
                        key = key,
                        modifier = mod,
                        pressed = mix(seed, key, 0.42f),
                        accent = accent,
                        accentContainer = mix(seed, bg, 0.70f),
                        onAccent = onAccent,
                        onAccentContainer = mix(seed, 0xFFFFFFFF.toInt(), 0.55f),
                        text = 0xFFE8E2EA.toInt(),
                        subtle = mix(seed, bg, 0.62f),
                        preview = mix(seed, key, 0.25f),
                        onPreview = 0xFFFFFFFF.toInt()
                    )
                } else {
                    val bg = mix(seed, 0xFFFDF8FF.toInt(), 0.94f)
                    val key = mix(seed, 0xFFFFFFFF.toInt(), 0.91f)
                    val mod = mix(seed, 0xFFFFFFFF.toInt(), 0.82f)
                    val accent = mix(seed, 0xFF000000.toInt(), 0.12f)
                    val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.5) {
                        0xFF1A1118.toInt()
                    } else Color.WHITE
                    Palette(
                        background = bg,
                        key = key,
                        modifier = mod,
                        pressed = mix(seed, key, 0.34f),
                        accent = accent,
                        accentContainer = mix(seed, 0xFFFFFFFF.toInt(), 0.78f),
                        onAccent = onAccent,
                        onAccentContainer = mix(seed, 0xFF000000.toInt(), 0.55f),
                        text = 0xFF1D1B20.toInt(),
                        subtle = mix(seed, bg, 0.58f),
                        preview = mix(seed, 0xFFFFFFFF.toInt(), 0.80f),
                        onPreview = 0xFF1D1B20.toInt()
                    )
                }
            }
        }
    }
}
