package com.nxnson.vesnavi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nxnson.vesnavi.navcore.Language
import java.util.Locale

/** The language picked in Settings: Vietnamese (default), English, or whatever Android uses. */
enum class AppLanguage(val key: String) {
    VI("vi"),
    EN("en"),
    SYSTEM("system");

    val resolved: Language
        get() = when (this) {
            VI -> Language.VI
            EN -> Language.EN
            SYSTEM -> if (Locale.getDefault().language == "vi") Language.VI else Language.EN
        }

    /** Each option names itself in its own language, so it can be found whatever is shown now. */
    val label: String
        get() = when (this) {
            VI -> "Tiếng Việt"
            EN -> "English"
            SYSTEM -> L("Theo ngôn ngữ điện thoại", "Same as the phone")
        }

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: VI
    }
}

/** Current app language. Composables that call [L] recompose on a switch (snapshot state). */
object Localizer {
    var choice by mutableStateOf(AppLanguage.VI)
        private set
    var language by mutableStateOf(Language.VI)
        private set

    fun load(settings: Settings) {
        choice = AppLanguage.fromKey(settings.appLanguage)
        language = choice.resolved
    }

    fun set(newChoice: AppLanguage, settings: Settings) {
        settings.appLanguage = newChoice.key
        choice = newChoice
        language = newChoice.resolved
    }

    /** For dates and numbers formatted in code. */
    val locale: Locale get() = if (language == Language.VI) Locale("vi", "VN") else Locale.US
}

/** Vietnamese / English pair, picked by the current app language. */
fun L(vi: String, en: String): String = Localizer.language.pick(vi, en)

/** Current app language (for navcore text: instructions, TFT lines). */
val appLanguage: Language get() = Localizer.language
