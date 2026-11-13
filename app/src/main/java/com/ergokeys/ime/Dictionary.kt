package com.ergokeys.ime

import android.content.Context
import java.io.File
import java.util.Collections

/**
 * Полностью локальный словарь.
 *
 * База: assets/ru.txt и assets/en.txt (частотный порядок, без сети).
 * Пользовательские слова и выученная частота: files/dictionary_user.txt.
 * Никаких облаков и ИИ — только сортировка, префиксы и расстояние Левенштейна.
 */
class Dictionary(private val context: Context) {

    private val words = ArrayList<String>(4096)
    private val baseScore = HashMap<String, Int>(4096)
    private val userScore = HashMap<String, Int>()
    private val userFile = File(context.filesDir, "dictionary_user.txt")
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        loadAsset("ru.txt")
        loadAsset("en.txt")
        words.sort()
        loadUserWords()
    }

    private fun loadAsset(name: String) {
        runCatching {
            context.assets.open(name).bufferedReader().useLines { lines ->
                var rank = 0
                lines.forEach { raw ->
                    val word = raw.trim().lowercase()
                    if (!isValid(word)) return@forEach
                    rank++
                    if (baseScore.containsKey(word)) return@forEach
                    words.add(word)
                    baseScore[word] = 10_000 - rank
                }
            }
        }
    }

    private fun isValid(word: String): Boolean {
        if (word.length < 2 || word.length > 32) return false
        return word.all { it in 'а'..'я' || it == 'ё' || it in 'a'..'z' || it == '-' }
    }

    private fun loadUserWords() {
        if (!userFile.exists()) return
        runCatching {
            userFile.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size == 2) {
                    val word = parts[0].trim().lowercase()
                    val count = parts[1].toIntOrNull() ?: 0
                    if (isValid(word) && count > 0) {
                        userScore[word] = count
                        insertWord(word)
                    }
                }
            }
        }
    }

    private fun insertWord(word: String) {
        val index = Collections.binarySearch(words, word)
        if (index < 0) words.add(-index - 1, word)
    }

    fun addUserWord(raw: String) {
        ensureLoaded()
        val word = raw.trim().lowercase()
        if (!isValid(word)) return
        userScore[word] = (userScore[word] ?: 0) + 10
        insertWord(word)
        persist()
    }

    fun removeUserWord(raw: String) {
        ensureLoaded()
        val word = raw.trim().lowercase()
        userScore.remove(word)
        if (!baseScore.containsKey(word)) words.remove(word)
        persist()
    }

    fun userWords(): List<String> {
        ensureLoaded()
        return userScore.entries
            .filter { it.key !in baseScore }
            .sortedByDescending { it.value }
            .map { it.key }
    }

    fun clearUserWords() {
        userScore.clear()
        val rebuilt = ArrayList(words.filter { baseScore.containsKey(it) })
        words.clear()
        words.addAll(rebuilt)
        persist()
    }

    /** Запоминаем частоту только для уже известных слов — без автодобавления мусора. */
    fun learn(word: String) {
        ensureLoaded()
        val w = word.lowercase()
        if (!isKnown(w)) return
        userScore[w] = ((userScore[w] ?: 0) + 1).coerceAtMost(5000)
    }

    fun isKnown(word: String): Boolean {
        ensureLoaded()
        val w = word.lowercase()
        return baseScore.containsKey(w) || userScore.containsKey(w)
    }

    fun persist() {
        runCatching {
            val tmp = File(userFile.parentFile, userFile.name + ".tmp")
            tmp.bufferedWriter().use { out ->
                userScore.entries
                    .sortedByDescending { it.value }
                    .forEach { (word, count) -> out.write("$word\t$count\n") }
            }
            tmp.renameTo(userFile)
        }
    }

    private fun score(word: String): Int =
        (baseScore[word] ?: 0) + (userScore[word] ?: 0) * 15_000

    /**
     * Подсказки: сначала точное продолжение, потом (если совсем мало) одна
     * очевидная опечатка. Никакой агрессивной автозамены.
     */
    fun suggestions(prefixRaw: String, limit: Int = 3): List<String> {
        ensureLoaded()
        val prefix = prefixRaw.lowercase()
        if (prefix.isEmpty()) {
            return userScore.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
        }

        val result = ArrayList<Pair<String, Int>>(64)
        var index = lowerBound(prefix)
        var scanned = 0
        while (index < words.size && scanned < 120) {
            val word = words[index]
            if (!word.startsWith(prefix)) break
            if (word != prefix) result.add(word to score(word))
            index++
            scanned++
        }

        if (result.size < limit && prefix.length >= 3) {
            val maxDistance = if (prefix.length >= 6) 2 else 1
            var i = lowerBound(prefix.substring(0, 1))
            var fuzzyScanned = 0
            while (i < words.size && fuzzyScanned < 4000) {
                val word = words[i]
                if (word.isEmpty() || word[0] != prefix[0]) break
                if (word.length in (prefix.length - maxDistance)..(prefix.length + maxDistance)) {
                    if (distanceAtMost(word, prefix, maxDistance)) {
                        result.add(word to (score(word) - 40_000))
                    }
                }
                i++
                fuzzyScanned++
            }
        }

        return result
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
            .map { it.first }
            .distinct()
            .take(limit)
    }

    /** Возвращает исправление только для явной опечатки; иначе null. */
    fun softCorrection(wordRaw: String): String? {
        ensureLoaded()
        val word = wordRaw.lowercase()
        if (word.length < 4 || isKnown(word)) return null
        val candidate = suggestions(word, 1).firstOrNull() ?: return null
        if (candidate.equals(word, ignoreCase = true)) return null
        if (absLengthDiff(candidate, word) > 1) return null
        if (!distanceAtMost(candidate, word, 1)) return null
        return candidate
    }

    private fun absLengthDiff(a: String, b: String) = kotlin.math.abs(a.length - b.length)

    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun distanceAtMost(a: String, b: String, maxDistance: Int): Boolean {
        if (kotlin.math.abs(a.length - b.length) > maxDistance) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            if (edits == maxDistance) return false
            edits++
            if (a.length > b.length) i++
            else if (a.length < b.length) j++
            else {
                i++
                j++
            }
        }
        edits += (a.length - i) + (b.length - j)
        return edits <= maxDistance
    }
}
