package com.ergokeys.ime

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Безлимитный локальный буфер обмена: хранит всю историю в files/clipboard.json.
 * Дубликаты поднимаются наверх, старые записи не выкидываются.
 */
class ClipboardStore(context: Context) {

    private val file = File(context.filesDir, "clipboard.json")
    private val items = ArrayList<String>()
    private val lock = Any()

    init {
        runCatching {
            if (file.exists()) {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val text = array.optString(i, "")
                    if (text.isNotBlank()) items.add(text)
                }
            }
        }
    }

    fun add(text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            items.remove(text)
            items.add(0, text)
            save()
        }
    }

    fun all(): List<String> = synchronized(lock) { items.toList() }

    fun clear() = synchronized(lock) {
        items.clear()
        save()
    }

    fun size(): Int = synchronized(lock) { items.size }

    private fun save() {
        runCatching {
            val array = JSONArray()
            items.forEach { array.put(it) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(file)
        }
    }
}
