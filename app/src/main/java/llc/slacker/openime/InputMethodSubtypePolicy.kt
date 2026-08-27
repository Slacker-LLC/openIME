package llc.slacker.openime

internal enum class ImeSubtypeLanguage {
    CHINESE,
    ENGLISH,
    UNKNOWN,
}

internal object InputMethodSubtypePolicy {
    fun language(locale: String?): ImeSubtypeLanguage {
        val normalized = locale.orEmpty().trim().replace('-', '_').lowercase()
        return when {
            normalized == "zh" || normalized.startsWith("zh_") -> ImeSubtypeLanguage.CHINESE
            normalized == "en" || normalized.startsWith("en_") -> ImeSubtypeLanguage.ENGLISH
            else -> ImeSubtypeLanguage.UNKNOWN
        }
    }

    fun defaultKeyboardMode(
        editorKind: EditorInfoAdapter.EditorKind,
        subtypeLocale: String?,
    ): KeyboardMode = when (editorKind) {
        EditorInfoAdapter.EditorKind.NUMBER,
        EditorInfoAdapter.EditorKind.DECIMAL,
        EditorInfoAdapter.EditorKind.PHONE,
        -> KeyboardMode.DIGITS

        EditorInfoAdapter.EditorKind.EMAIL,
        EditorInfoAdapter.EditorKind.URL,
        EditorInfoAdapter.EditorKind.PASSWORD,
        -> KeyboardMode.ENGLISH_26

        else -> when (language(subtypeLocale)) {
            ImeSubtypeLanguage.ENGLISH -> KeyboardMode.ENGLISH_26
            ImeSubtypeLanguage.CHINESE,
            ImeSubtypeLanguage.UNKNOWN,
            -> KeyboardMode.PINYIN_26
        }
    }
}
