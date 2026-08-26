package llc.slacker.openime

import android.view.inputmethod.EditorInfo

enum class KeyboardMode {
    PINYIN_26,
    ENGLISH_26,
    PINYIN_9,
    ENGLISH_T9,
    DIGITS,
}

enum class Panel {
    NONE,
    TOOLS,
    KEYBOARD_SELECT,
    SYMBOLS,
    EMOJI,
    HANDWRITING,
    VOICE,
    CLIPBOARD,
    TEXT_EDITOR,
    SETTINGS,
    FUZZY_SETTINGS,
    GAMING,
    CANDIDATE_EXPANDED,
}

enum class ShiftState {
    LOWERCASE,
    SHIFT_ONCE,
    CAPS_LOCK,
}

enum class ImeAppearance(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

data class ImeState(
    val keyboardMode: KeyboardMode = KeyboardMode.PINYIN_26,
    val previousKeyboardMode: KeyboardMode = KeyboardMode.PINYIN_26,
    val panel: Panel = Panel.NONE,
    val composition: String = "",
    val candidates: List<String> = emptyList(),
    val expandedCandidates: List<String> = emptyList(),
    val shiftState: ShiftState = ShiftState.LOWERCASE,
    val pinyin9Filters: List<String> = emptyList(),
    val selectedPinyin9Filter: String = "",
    val theme: ImeTheme = ImeTheme.IOS,
    val appearance: ImeAppearance = ImeAppearance.SYSTEM,
    val soundEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
    val popupEnabled: Boolean = true,
    val fuzzyPinyinEnabled: Boolean = false,
    val editorInfo: EditorInfo? = null,
    val editorAction: Int = EditorInfo.IME_ACTION_NONE,
    val passwordField: Boolean = false,
    val symbolCategory: String = "常用",
    val emojiCategory: String = "表情",
    val voiceState: VoiceUiState = VoiceUiState(),
    val skinOpacity: Int = 95,
    val skinRadius: Int = 8,
    val skinFontSize: Int = 17,
    val skinPrimaryColor: String = "#2563eb",
) {
    fun withMode(mode: KeyboardMode): ImeState = copy(
        previousKeyboardMode = keyboardMode,
        keyboardMode = mode,
        composition = "",
        candidates = emptyList(),
    )
}

data class VoiceUiState(
    val listening: Boolean = false,
    val partialText: String = "",
    val finalText: String = "",
    val language: String = "普通话",
    val message: String = "",
)
