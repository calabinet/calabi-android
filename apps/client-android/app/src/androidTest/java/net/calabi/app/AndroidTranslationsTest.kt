package net.calabi.app

import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import java.util.Locale

/** Exercises Android's actual locale lookup, plural selection and Formatter on the device. */
class AndroidTranslationsTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        try {
            result.putString("stream", verifyAndroidTranslations(targetContext))
            finish(0, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(1, result)
        }
    }
}

internal fun verifyAndroidTranslations(targetContext: Context): String {
    val locales = linkedMapOf(
        "en" to "Keyboard", "zh-CN" to "键盘", "zh-TW" to "鍵盤",
        "de" to "Tastatur", "es" to "Teclado", "fr" to "Clavier",
        "it" to "Tastiera", "ja" to "キーボード", "ko" to "키보드", "pt" to "Teclado",
    )
    fun context(tag: String) = targetContext.createConfigurationContext(
        Configuration(targetContext.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        })
    val english = context("en")
    val specifier = Regex("%(\\d+)\\$([dsf])")
    var formatted = 0
    var pluralSelections = 0
    for ((tag, keyboard) in locales) {
        val localized = context(tag)
        check(localized.getString(R.string.rdp_keyboard) == keyboard) { "$tag: RDP locale fallback" }
        if (tag != "en") {
            for (id in listOf(R.string.files_error_save_cleanup, R.string.sh_pin_mismatch,
                R.string.background_steps_huawei, R.string.rdp_hardware_hint)) {
                check(localized.getString(id) != english.getString(id)) { "$tag: untranslated resource $id" }
            }
        }
        for (field in R.string::class.java.fields) {
            val id = field.getInt(null)
            val specs = specifier.findAll(english.getString(id)).toList()
            if (specs.isEmpty()) continue
            val arguments = Array<Any>(specs.maxOf { it.groupValues[1].toInt() }) { "test-device" }
            for (spec in specs) arguments[spec.groupValues[1].toInt() - 1] = when (spec.groupValues[2]) {
                "d" -> 12L
                "f" -> 1.5
                else -> "test-device"
            }
            val text = localized.getString(id, *arguments)
            check(!specifier.containsMatchIn(text)) { "$tag: unexpanded format in ${field.name}" }
            formatted++
        }
        for (count in listOf(0, 1, 2, 5, 21, 1000000)) {
            for (field in R.plurals::class.java.fields) {
                val id = field.getInt(null)
                val text = localized.resources.getQuantityString(id, count, count)
                check(text.isNotBlank() && !specifier.containsMatchIn(text)) { "$tag: plural $id/$count" }
                pluralSelections++
            }
        }
    }
    val englishResources = english.resources
    check(englishResources.getQuantityString(R.plurals.files_send, 1, 1) == "Send 1 selected file")
    check(englishResources.getQuantityString(R.plurals.files_send, 2, 2) == "Send 2 selected files")
    check(englishResources.getQuantityString(R.plurals.access_retention, 1, 1) == "Kept for 1 day.")
    check(englishResources.getQuantityString(R.plurals.access_retention, 2, 2) == "Kept for 2 days.")
    check(englishResources.getQuantityString(R.plurals.access_truncated, 1, 1) == "Showing the latest 1 row.")
    return "All 10 supported locales: lookup anchors, $formatted formatted strings and $pluralSelections plural selections passed.\n"
}
