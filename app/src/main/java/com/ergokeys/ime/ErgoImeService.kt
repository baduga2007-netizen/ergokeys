package com.ergokeys.ime

import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class ErgoImeService : InputMethodService(), KeyboardView.Listener {

    private lateinit var prefs: ErgoPrefs
    private lateinit var dictionary: Dictionary
    private lateinit var clipboardStore: ClipboardStore
    private lateinit var keyboardView: KeyboardView
    private lateinit var root: FrameLayout
    private var panel: LinearLayout? = null
    private var panelVisible = false
    private var navBarInset = -1
    private val handler = Handler(Looper.getMainLooper())

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        captureClipboard()
        if (panelVisible) rebuildPanelItems()
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (::keyboardView.isInitialized) {
            when (key) {
                "language" -> keyboardView.setLanguage(prefs.language)
                "height_percent" -> applyKeyboardHeight()
                else -> keyboardView.refreshPreferences()
            }
            updateSuggestions()
        }
    }

    override fun onCreate() {
        super.onCreate()
        SharedData.init(this)
        prefs = ErgoPrefs(this)
        dictionary = SharedData.dictionary
        clipboardStore = SharedData.clipboard
        prefs.register(prefsListener)
    }

    override fun onCreateInputView(): View {
        root = FrameLayout(this)
        panel = null
        panelVisible = false
        keyboardView = KeyboardView(this, prefs, this)
        keyboardView.setLanguage(prefs.language)
        root.setBackgroundColor(keyboardView.panelColors().background)
        root.addView(
            keyboardView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                keyboardHeightPx()
            )
        )
        applyNavBarInset()
        root.setOnApplyWindowInsetsListener { _, insets ->
            val inset = insetBottom(insets)
            if (inset > 0) setNavBarInset(inset) else syncNavBarInset()
            insets
        }
        // Окно клавиатуры могло «прилипнуть» к низу экрана ещё до прихода insets:
        // в этом случае считаем отступ сами, по реальному положению окна.
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncNavBarInset() }
        applyKeyboardHeight()
        return root
    }

    /**
     * Поднимает клавиатуру над системной панелью навигации (кнопки или полоса жеста),
     * иначе нижний ряд уезжает под неё.
     */
    private fun applyNavBarInset() {
        if (!::root.isInitialized) return
        syncNavBarInset()
    }

    private fun setNavBarInset(px: Int) {
        if (!::root.isInitialized) return
        val safe = px.coerceAtLeast(0)
        if (safe == navBarInset && root.paddingBottom == safe) return
        navBarInset = safe
        root.setPadding(0, 0, 0, safe)
    }

    /**
     * Системные insets в окне клавиатуры часто приходят нулевыми, поэтому
     * дополнительно проверяем, доходит ли окно до самого низа экрана.
     */
    private fun syncNavBarInset() {
        if (!::root.isInitialized || root.height == 0) return
        val fromInsets = insetBottom(root.rootWindowInsets)
        if (fromInsets > 0) {
            setNavBarInset(fromInsets)
            return
        }
        setNavBarInset(if (reachesScreenBottom()) fallbackNavBar() else 0)
    }

    private fun insetBottom(insets: WindowInsets?): Int {
        if (insets == null) return 0
        // Панель навигации, полоса жестов и системные жесты — берём максимум.
        val types = WindowInsets.Type.navigationBars() or
            WindowInsets.Type.mandatorySystemGestures() or
            WindowInsets.Type.systemGestures()
        return insets.getInsets(types).bottom
    }

    private fun reachesScreenBottom(): Boolean {
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val wm = getSystemService(WindowManager::class.java)
        val screenBottom = wm?.currentWindowMetrics?.bounds?.bottom
            ?: resources.displayMetrics.heightPixels
        return loc[1] + root.height >= screenBottom - dp(2)
    }

    private fun fallbackNavBar(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id > 0) {
            val px = resources.getDimensionPixelSize(id)
            if (px > 0) return px
        }
        return dp(44)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView.refreshPreferences()
        keyboardView.setLanguage(prefs.language)
        keyboardView.setMode(KeyboardView.Mode.LETTERS)
        hidePanel()
        applyNavBarInset()
        handler.post { captureClipboard() }
        if (isSensitiveInput()) keyboardView.setSuggestions(emptyList()) else updateSuggestions()
        updateAutoShift(force = true)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        applyNavBarInset()
        captureClipboard()
        runCatching {
            getSystemService(ClipboardManager::class.java)
                ?.addPrimaryClipChangedListener(clipListener)
        }
    }

    override fun onWindowHidden() {
        runCatching {
            getSystemService(ClipboardManager::class.java)
                ?.removePrimaryClipChangedListener(clipListener)
        }
        super.onWindowHidden()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        dictionary.persist()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        dictionary.persist()
        prefs.unregister(prefsListener)
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::keyboardView.isInitialized) {
            keyboardView.refreshPreferences()
            applyKeyboardHeight()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        if (newSelStart == newSelEnd) updateSuggestions()
    }

    private fun keyboardHeightPx(): Int {
        val screen = resources.displayMetrics.heightPixels
        return (screen * prefs.heightPercent / 100f).toInt()
            .coerceAtLeast((260 * resources.displayMetrics.density).toInt())
            .coerceAtMost((screen * 0.56f).toInt())
    }

    private fun applyKeyboardHeight() {
        if (!::keyboardView.isInitialized) return
        val h = keyboardHeightPx()
        keyboardView.layoutParams = (keyboardView.layoutParams ?: FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h
        )).also { it.height = h }
        panel?.layoutParams = (panel?.layoutParams ?: FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h
        )).also { it.height = h }
    }

    // ------------------------------------------------------------- listener

    override fun onChar(text: String) {
        inputConnection()?.commitText(text, 1)
        updateSuggestions()
        updateAutoShift()
    }

    override fun onSpace() {
        val ic = inputConnection() ?: return
        val before = ic.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        val word = trailingWord(before)

        if (prefs.softCorrect && !isSensitiveInput() && word.length >= 4 && shouldCorrect(before, word)) {
            val fixed = dictionary.softCorrection(word)
            if (fixed != null) {
                ic.deleteSurroundingText(word.length, 0)
                ic.commitText("$fixed ", 1)
                dictionary.learn(fixed)
                keyboardView.setSuggestions(emptyList())
                updateAutoShift()
                return
            }
        }

        if (word.isNotBlank()) dictionary.learn(word)
        ic.commitText(" ", 1)
        updateSuggestions()
        updateAutoShift()
    }

    private fun shouldCorrect(before: String, word: String): Boolean {
        // Имена и слова с заглавной буквы не «исправляем».
        if (word.firstOrNull()?.isUpperCase() == true) return false
        // Слова из локального словаря (сленг, мат, имена) неприкосновенны.
        if (dictionary.isKnown(word)) return false
        return before.length >= word.length
    }

    override fun onBackspace() {
        val ic = inputConnection() ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        updateSuggestions()
    }

    override fun onDeleteWord() {
        val ic = inputConnection() ?: return
        val before = ic.getTextBeforeCursor(160, 0)?.toString().orEmpty()
        if (before.isEmpty()) return

        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        val count = before.length - start
        if (count > 0) ic.deleteSurroundingText(count, 0)
        updateSuggestions()
        updateAutoShift()
    }

    override fun onEnter() {
        val ic = inputConnection() ?: return
        val info = currentInputEditorInfo
        val options = info?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noEnterAction = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            if (sendDefaultEditorAction(true)) return
        }
        ic.commitText("\n", 1)
        updateAutoShift(force = true)
    }

    override fun onMoveCursor(delta: Int) {
        val ic = inputConnection() ?: return
        val keyCode = if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(abs(delta)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        updateSuggestions()
    }

    override fun onLanguage() {
        val next = if (prefs.language == "ru") "en" else "ru"
        prefs.language = next
        keyboardView.setLanguage(next)
        keyboardView.setMode(KeyboardView.Mode.LETTERS)
        updateSuggestions()
    }

    override fun onClipboard() {
        if (panelVisible) hidePanel() else showPanel()
    }

    override fun onSuggestion(word: String) {
        val ic = inputConnection() ?: return
        val before = ic.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        val prefix = trailingWord(before)
        if (prefix.isNotEmpty()) ic.deleteSurroundingText(prefix.length, 0)
        ic.commitText(word, 1)
        dictionary.learn(word)
        keyboardView.setSuggestions(emptyList())
        updateSuggestions()
    }

    // ------------------------------------------------------------- helpers

    private fun inputConnection(): InputConnection? = currentInputConnection

    private fun trailingWord(text: String): String {
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0) {
            val c = text[start - 1]
            if (c.isLetter() || c == '-' || c == '\'') start-- else break
        }
        return text.substring(start, end)
    }

    private fun updateSuggestions() {
        if (!::keyboardView.isInitialized) return
        if (!prefs.suggestions || isSensitiveInput()) {
            keyboardView.setSuggestions(emptyList())
            return
        }
        val before = inputConnection()?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val word = trailingWord(before)
        val list = if (word.length >= 2) dictionary.suggestions(word, 3) else emptyList()
        keyboardView.setSuggestions(
            list.filter { !it.equals(word, ignoreCase = true) }
        )
    }

    private fun isSensitiveInput(): Boolean {
        val type = currentInputEditorInfo?.inputType ?: return false
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        return cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_NUMBER ||
            cls == InputType.TYPE_CLASS_DATETIME ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun updateAutoShift(force: Boolean = false) {
        if (!force && keyboardView.shift != KeyboardView.ShiftState.OFF) return
        val before = inputConnection()?.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val sentenceStart = before.isEmpty() ||
            before == " " ||
            before.endsWith(". ") ||
            before.endsWith("! ") ||
            before.endsWith("? ") ||
            before.endsWith("\n") ||
            before.lastOrNull() == '.' ||
            before.lastOrNull() == '!' ||
            before.lastOrNull() == '?'
        if (sentenceStart) keyboardView.setShift(KeyboardView.ShiftState.ONCE)
    }

    private fun captureClipboard() {
        val text = readSystemClipboard() ?: return
        if (!prefs.clipboardHistory) return
        clipboardStore.add(text)
        if (panelVisible) rebuildPanelItems()
    }

    private fun readSystemClipboard(): String? = runCatching {
        val cm = getSystemService(ClipboardManager::class.java) ?: return null
        if (!cm.hasPrimaryClip()) return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        text.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun pushSystemClipboard(text: String) {
        runCatching {
            val cm = getSystemService(ClipboardManager::class.java) ?: return
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ErgoKeys", text))
        }
    }

    // --------------------------------------------------------- clipboard UI

    private fun showPanel() {
        captureClipboard()
        val current = panel ?: buildPanel().also { created ->
            panel = created
            root.addView(
                created,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeightPx())
            )
        }
        current.visibility = View.VISIBLE
        rebuildPanelItems()
        keyboardView.cancelPress()
        keyboardView.visibility = View.GONE
        panelVisible = true
    }

    private fun hidePanel() {
        if (::keyboardView.isInitialized) keyboardView.cancelPress()
        keyboardView.visibility = View.VISIBLE
        panel?.visibility = View.GONE
        panelVisible = false
    }

    private fun buildPanel(): LinearLayout {
        val colors = keyboardView.panelColors()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(colors.background)
                cornerRadii = floatArrayOf(dp(24f), dp(24f), dp(24f), dp(24f), 0f, 0f, 0f, 0f)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Буфер обмена"
            setTextColor(colors.text)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            tag = "clip_title"
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val paste = panelButton("Вставить") {
            val text = readSystemClipboard()
            if (text.isNullOrBlank()) {
                android.widget.Toast.makeText(this, "В буфере пусто", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                clipboardStore.add(text)
                inputConnection()?.commitText(text, 1)
                hidePanel()
            }
        }
        val clear = panelButton("Стереть") {
            clipboardStore.clear()
            rebuildPanelItems()
        }
        val close = panelButton("Готово") { hidePanel() }
        header.addView(paste)
        header.addView(clear)
        header.addView(close)
        container.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val hint = TextView(this).apply {
            text = "История не ограничена. Нажмите на запись, чтобы вставить её в текст."
            setTextColor((colors.text and 0x00FFFFFF) or (0x99 shl 24))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(6))
        }
        container.addView(hint)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "clip_list"
        }
        scroll.addView(list)
        container.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return container
    }

    private fun rebuildPanelItems() {
        val panel = panel ?: return
        val list = panel.findViewWithTag<LinearLayout>("clip_list") ?: return
        list.removeAllViews()
        val colors = keyboardView.panelColors()
        val items = clipboardStore.all()

        panel.findViewWithTag<TextView>("clip_title")?.text =
            if (items.isEmpty()) "Буфер обмена" else "Буфер обмена · ${items.size}"

        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Пока пусто. Скопируйте текст в любом приложении — запись появится здесь."
                setTextColor(colors.text)
                textSize = 14f
                setPadding(dp(8), dp(20), dp(8), dp(8))
            })
            return
        }

        val visible = items.take(300)
        if (items.size > visible.size) {
            list.addView(TextView(this).apply {
                text = "Показаны последние ${visible.size} из ${items.size}. Всё сохранено локально."
                setTextColor(colors.text)
                textSize = 12f
                setPadding(dp(8), dp(4), dp(8), dp(8))
            })
        }

        visible.forEach { text ->
            val item = TextView(this).apply {
                this.text = text
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(colors.text)
                textSize = 14f
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = GradientDrawable().apply {
                    setColor(colors.container)
                    cornerRadius = dp(16f)
                }
                setOnClickListener {
                    inputConnection()?.commitText(text, 1)
                    pushSystemClipboard(text)
                    clipboardStore.add(text)
                    hidePanel()
                }
            }
            list.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) })
        }
    }

    private fun panelButton(label: String, onClick: () -> Unit): TextView {
        val colors = keyboardView.panelColors()
        return TextView(this).apply {
            text = label
            setTextColor(colors.accentText)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                setColor(colors.container)
                cornerRadius = dp(14f)
            }
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(6)
            layoutParams = lp
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
