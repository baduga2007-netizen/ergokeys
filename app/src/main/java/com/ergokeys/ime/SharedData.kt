package com.ergokeys.ime

import android.content.Context

/**
 * Один словарь и один буфер на процесс: настройки и клавиатура видят
 * одинаковые данные без перезагрузки файлов.
 */
object SharedData {

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    val dictionary: Dictionary by lazy {
        Dictionary(requireContext()).also { it.ensureLoaded() }
    }

    val clipboard: ClipboardStore by lazy {
        ClipboardStore(requireContext())
    }

    private fun requireContext(): Context = appContext
        ?: error("SharedData.init(context) must be called first")
}
