package com.ergokeys.ime

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Shapes
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ErgoTheme {
                SettingsScreen()
            }
        }
    }
}

private val ErgoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
private fun ErgoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        MaterialTheme.colorScheme
    }
    val typography = Typography().let {
        it.copy(
            headlineSmall = it.headlineSmall.copy(fontWeight = FontWeight.Bold),
            titleLarge = it.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = ErgoShapes,
        typography = typography,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { ErgoPrefs(context) }
    val dictionary = remember {
        SharedData.init(context)
        SharedData.dictionary
    }
    val clipboard = remember {
        SharedData.init(context)
        SharedData.clipboard
    }

    var height by remember { mutableIntStateOf(prefs.heightPercent) }
    var hitbox by remember { mutableIntStateOf(prefs.hitboxPercent) }
    var haptics by remember { mutableStateOf(prefs.haptics) }
    var oneHand by remember { mutableIntStateOf(prefs.oneHand) }
    var suggestions by remember { mutableStateOf(prefs.suggestions) }
    var softCorrect by remember { mutableStateOf(prefs.softCorrect) }
    var cursorHint by remember { mutableStateOf(prefs.cursorHint) }
    var clipboardHistory by remember { mutableStateOf(prefs.clipboardHistory) }
    var language by remember { mutableStateOf(prefs.language) }

    var newWord by remember { mutableStateOf("") }
    var userWords by remember { mutableStateOf(dictionary.userWords()) }
    var clipCount by remember { mutableIntStateOf(clipboard.size()) }

    val scheme = MaterialTheme.colorScheme
    val background = Brush.linearGradient(
        listOf(
            scheme.surface,
            scheme.surfaceContainerLowest,
            scheme.surface
        )
    )

    val openImeSettings: () -> Unit = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    val openPicker: () -> Unit = {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.showInputMethodPicker()
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxSize()
            .background(background)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
                    Text(
                        "ЭргоКлавиатура",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "локально · без облака и ИИ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { HeroCard(onEnable = { openImeSettings() }, onPick = { openPicker() }) }

            item {
                SectionCard(
                    title = "Эргономика",
                    subtitle = "Клавиатура прощает спешку, а не переучивает."
                ) {
                    ValueSlider(
                        title = "Высота клавиатуры",
                        valueLabel = "$height%",
                        value = height.toFloat(),
                        range = 26f..50f,
                        steps = 23,
                        onChange = {
                            height = it.roundToInt()
                            prefs.heightPercent = height
                        }
                    )
                    ValueSlider(
                        title = "Хитбокс клавиши",
                        valueLabel = "$hitbox%",
                        value = hitbox.toFloat(),
                        range = 100f..150f,
                        steps = 9,
                        onChange = {
                            hitbox = it.roundToInt()
                            prefs.hitboxPercent = hitbox
                        }
                    )
                    Text(
                        "Визуал клавиши остаётся 100%, а зона нажатия расширяется. " +
                            "Промах между клавишами выбирает ближайшую.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    SwitchRow(
                        title = "Виброотклик",
                        subtitle = "Короткий отклик на каждое касание",
                        checked = haptics
                    ) {
                        haptics = it
                        prefs.haptics = it
                    }
                    SwitchRow(
                        title = "Подсказка курсора",
                        subtitle = "Показывать «← 3» при свайпе по пробелу",
                        checked = cursorHint
                    ) {
                        cursorHint = it
                        prefs.cursorHint = it
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Режим одной руки", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("Выкл", "Слева", "Справа").forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = oneHand == index,
                                onClick = {
                                    oneHand = index
                                    prefs.oneHand = index
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, 3),
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "Язык и подсказки",
                    subtitle = "ЙЦУКЕН и QWERTY, свайпы только для навигации."
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("Русский" to "ru", "English" to "en").forEachIndexed { index, item ->
                            SegmentedButton(
                                selected = language == item.second,
                                onClick = {
                                    language = item.second
                                    prefs.language = item.second
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                                label = { Text(item.first) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SwitchRow(
                        title = "Подсказки из словаря",
                        subtitle = "Сленг, имена и мат не «исправляются»",
                        checked = suggestions
                    ) {
                        suggestions = it
                        prefs.suggestions = it
                    }
                    SwitchRow(
                        title = "Мягкая автозамена",
                        subtitle = "Только очевидная опечатка, имена не трогаем",
                        checked = softCorrect
                    ) {
                        softCorrect = it
                        prefs.softCorrect = it
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Свайп по пробелу — курсор. Свайп влево по ⌫ — удалить слово. " +
                            "Свайпы по буквам ничего не вводят: никакого «свайп-набора».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionCard(
                    title = "Локальный словарь",
                    subtitle = "Ваши слова хранятся только в памяти телефона."
                ) {
                    OutlinedTextField(
                        value = newWord,
                        onValueChange = { newWord = it },
                        label = { Text("Слово, сленг или имя") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                dictionary.addUserWord(newWord)
                                newWord = ""
                                userWords = dictionary.userWords()
                            },
                            enabled = newWord.isNotBlank()
                        ) { Text("Добавить") }
                        Spacer(Modifier.width(8.dp))
                        if (userWords.isNotEmpty()) {
                            TextButton(onClick = {
                                dictionary.clearUserWords()
                                userWords = dictionary.userWords()
                            }) { Text("Очистить мои слова") }
                        }
                    }
                    if (userWords.isEmpty()) {
                        Text(
                            "Пока только базовая база. Добавьте ники, имена и любимые словечки — " +
                                "они будут выше в подсказках.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            userWords.take(60).forEach { word ->
                                AssistChip(
                                    onClick = {
                                        dictionary.removeUserWord(word)
                                        userWords = dictionary.userWords()
                                    },
                                    label = { Text("$word ×") }
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "Буфер обмена",
                    subtitle = "Безлимитная история, без синхронизации."
                ) {
                    SwitchRow(
                        title = "Сохранять скопированное",
                        subtitle = "При показе клавиатуры новая запись попадает в историю",
                        checked = clipboardHistory
                    ) {
                        clipboardHistory = it
                        prefs.clipboardHistory = it
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Записей: $clipCount",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            clipboard.clear()
                            clipCount = clipboard.size()
                        }) { Text("Очистить") }
                    }
                    Text(
                        "Кнопка 📋 на клавиатуре открывает историю. Записи не удаляются " +
                            "сами и не уходят в сеть.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionCard(title = "О приложении") {
                    Text(
                        "ЭргоКлавиатура — эргономичный слой поверх ЙЦУКЕН и QWERTY. " +
                            "Хитбокс 130% при визуале 100%, словарь и буфер обмена только на устройстве. " +
                            "Никаких аккаунтов, аналитики и облачных запросов.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(onClick = { openImeSettings() }) { Text("Настройки клавиатуры Android") }
                        TextButton(onClick = {
                            prefs.reset()
                            height = prefs.heightPercent
                            hitbox = prefs.hitboxPercent
                            haptics = prefs.haptics
                            oneHand = prefs.oneHand
                            suggestions = prefs.suggestions
                            softCorrect = prefs.softCorrect
                            cursorHint = prefs.cursorHint
                            clipboardHistory = prefs.clipboardHistory
                            language = prefs.language
                            Toast.makeText(context, "Настройки сброшены", Toast.LENGTH_SHORT).show()
                        }) { Text("Сбросить") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(onEnable: () -> Unit, onPick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⌨", fontSize = 30.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "Прощает спешку",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "Хитбокс 130% · свайпы для курсора · безлимитный буфер",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onEnable, modifier = Modifier.weight(1f)) {
                    Text("Включить", textAlign = TextAlign.Center)
                }
                FilledTonalButton(onClick = onPick, modifier = Modifier.weight(1f)) {
                    Text("Выбрать", textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Сначала включите клавиатуру в настройках Android, затем выберите её здесь.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ValueSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
