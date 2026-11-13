package com.ergokeys.ime

enum class KeyAction {
    CHAR, SHIFT, BACKSPACE, ENTER, SPACE, LANGUAGE, SYMBOLS, LETTERS, CLIPBOARD, NONE
}

enum class KeyKind { CHAR, MOD, ACCENT, SPACE }

data class KeySpec(
    val label: String,
    val action: KeyAction = KeyAction.CHAR,
    val output: String? = null,
    val weight: Float = 1f,
    val kind: KeyKind = KeyKind.CHAR,
    val alt: String? = null
)

data class RowSpec(val keys: List<KeySpec>)

data class LayoutSpec(val rows: List<RowSpec>)

/**
 * Раскладки строятся один раз и переиспользуются. Вес клавиш задан в колонках
 * сетки из 12 колонок: так строки визуально выровнены, а клавиши не «пляшут».
 */
object KeyboardLayouts {

    private const val COLUMNS = 12f

    private fun char(c: String, alt: String? = null) = KeySpec(c, output = c, alt = alt)

    private val shift = KeySpec("⇧", KeyAction.SHIFT, weight = 1.45f, kind = KeyKind.MOD)
    private val backspace = KeySpec("⌫", KeyAction.BACKSPACE, weight = 1.45f, kind = KeyKind.MOD)
    private val enter = KeySpec("↵", KeyAction.ENTER, weight = 1.55f, kind = KeyKind.ACCENT)
    private val space = KeySpec("", KeyAction.SPACE, weight = 4.5f, kind = KeyKind.SPACE)
    private val comma = KeySpec(",", output = ",", weight = 0.75f)
    private val period = KeySpec(".", output = ".", weight = 0.75f)
    private val sym = KeySpec("?123", KeyAction.SYMBOLS, weight = 1.15f, kind = KeyKind.MOD)
    private val letters = KeySpec("АБВ", KeyAction.LETTERS, weight = 1.15f, kind = KeyKind.MOD)
    private val clipboard = KeySpec("📋", KeyAction.CLIPBOARD, weight = 0.85f, kind = KeyKind.MOD)

    private fun languageKey(lang: String) = KeySpec(
        if (lang == "ru") "EN" else "РУ",
        KeyAction.LANGUAGE,
        weight = 1.05f,
        kind = KeyKind.MOD
    )

    private fun bottom(lang: String, leftLabel: String, leftAction: KeyAction) = RowSpec(
        listOf(
            KeySpec(leftLabel, leftAction, weight = 1.15f, kind = KeyKind.MOD),
            languageKey(lang),
            comma,
            space,
            period,
            enter,
            clipboard
        )
    )

    fun letters(lang: String): LayoutSpec {
        val rows = if (lang == "ru") {
            listOf(
                RowSpec("йцукенгшщзхъ".map { char(it.toString(), if (it == 'е') "ё" else null) }),
                RowSpec("фывапролджэ".map { char(it.toString()) }),
                RowSpec(
                    listOf(shift) + "ячсмитьбю".map {
                        char(it.toString(), if (it == 'ь') "ъ" else null)
                    } + backspace
                ),
                bottom(lang, "?123", KeyAction.SYMBOLS)
            )
        } else {
            listOf(
                RowSpec("qwertyuiop".map { char(it.toString()) }),
                RowSpec("asdfghjkl".map { char(it.toString()) }),
                RowSpec(listOf(shift) + "zxcvbnm".map { char(it.toString()) } + backspace),
                bottom(lang, "?123", KeyAction.SYMBOLS)
            )
        }
        return LayoutSpec(rows)
    }

    fun symbols(lang: String): LayoutSpec {
        val digits = RowSpec("1234567890".map { char(it.toString()) })
        val signs = RowSpec("@#\$%&*-+=/".map { char(it.toString()) })
        val punct = RowSpec(
            listOf(
                char("!"), char("?"), char(":"), char(";"), char("\""), char("'"),
                char("("), char(")"), char("["), char("]")
            ) + backspace
        )
        return LayoutSpec(
            listOf(
                digits,
                signs,
                punct,
                bottom(lang, "АБВ", KeyAction.LETTERS)
            )
        )
    }

    fun columns() = COLUMNS
}
