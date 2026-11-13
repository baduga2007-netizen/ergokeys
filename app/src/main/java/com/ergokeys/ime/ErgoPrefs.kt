package com.ergokeys.ime

import android.content.Context
import android.content.SharedPreferences

/**
 * Все настройки хранятся локально. Никакой сети, аккаунтов и телеметрии.
 */
class ErgoPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var language: String
        get() = sp.getString(KEY_LANGUAGE, "ru") ?: "ru"
        set(value) = sp.edit().putString(KEY_LANGUAGE, value).apply()

    /** Высота клавиатуры в процентах от высоты экрана. */
    var heightPercent: Int
        get() = sp.getInt(KEY_HEIGHT, 34).coerceIn(26, 50)
        set(value) = sp.edit().putInt(KEY_HEIGHT, value.coerceIn(26, 50)).apply()

    /** Во сколько раз хитбокс больше видимой клавиши. */
    var hitboxPercent: Int
        get() = sp.getInt(KEY_HITBOX, 130).coerceIn(100, 150)
        set(value) = sp.edit().putInt(KEY_HITBOX, value.coerceIn(100, 150)).apply()

    var haptics: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, false)
        set(value) = sp.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** 0 — выкл, 1 — слева, 2 — справа. */
    var oneHand: Int
        get() = sp.getInt(KEY_ONE_HAND, 0).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_ONE_HAND, value.coerceIn(0, 2)).apply()

    var suggestions: Boolean
        get() = sp.getBoolean(KEY_SUGGESTIONS, true)
        set(value) = sp.edit().putBoolean(KEY_SUGGESTIONS, value).apply()

    /** Мягкая автозамена: только очевидные опечатки, без переучивания. */
    var softCorrect: Boolean
        get() = sp.getBoolean(KEY_SOFT_CORRECT, true)
        set(value) = sp.edit().putBoolean(KEY_SOFT_CORRECT, value).apply()

    var clipboardHistory: Boolean
        get() = sp.getBoolean(KEY_CLIPBOARD, true)
        set(value) = sp.edit().putBoolean(KEY_CLIPBOARD, value).apply()

    /** Показывать подсказку «← N» при свайпе по пробелу. */
    var cursorHint: Boolean
        get() = sp.getBoolean(KEY_CURSOR_HINT, true)
        set(value) = sp.edit().putBoolean(KEY_CURSOR_HINT, value).apply()

    var lastShiftLock: Boolean
        get() = sp.getBoolean(KEY_SHIFT_LOCK, false)
        set(value) = sp.edit().putBoolean(KEY_SHIFT_LOCK, value).apply()

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun reset() {
        sp.edit().clear().apply()
    }

    companion object {
        private const val FILE = "ergokeys_settings"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_HEIGHT = "height_percent"
        private const val KEY_HITBOX = "hitbox_percent"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_ONE_HAND = "one_hand"
        private const val KEY_SUGGESTIONS = "suggestions"
        private const val KEY_SOFT_CORRECT = "soft_correct"
        private const val KEY_CLIPBOARD = "clipboard_history"
        private const val KEY_CURSOR_HINT = "cursor_hint"
        private const val KEY_SHIFT_LOCK = "shift_lock"
    }
}
