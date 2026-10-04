package llc.slacker.openime.core

import android.view.inputmethod.EditorInfo
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeTheme

enum class KeyboardMode {
    PINYIN_26,
    ENGLISH_26,
    PINYIN_9,
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
    CANDIDATE_EXPANDED,
}

enum class ShiftState {
    LOWERCASE,
    SHIFT_ONCE,
    CAPS_LOCK,
}


data class ImeState(
    val keyboardMode: KeyboardMode = KeyboardMode.PINYIN_26,
    val panel: Panel = Panel.NONE,
    val composition: String = "",
    val candidates: List<String> = emptyList(),
    val expandedCandidates: List<String> = emptyList(),
    val shiftState: ShiftState = ShiftState.LOWERCASE,
    val pinyin9Filters: List<String> = emptyList(),
    val selectedPinyin9Filter: String = "",
    val theme: ImeTheme = ImeTheme.IOS,
    val appearance: ImeAppearance = ImeAppearance.SYSTEM,
    val soundEnabled: Boolean = false,
    val hapticEnabled: Boolean = true,
    // Default off, matching ImeSettingsRepository.loadPopup and the View field.
    val popupEnabled: Boolean = true,
    val fuzzyPinyinEnabled: Boolean = false,
    val editorInfo: EditorInfo? = null,
    val passwordField: Boolean = false,
    val symbolCategory: String = "常用",
    // First real emoji category tab (see ImeData.emojiByCategory order).
    val emojiCategory: String = "笑脸",
    val voiceState: VoiceUiState = VoiceUiState(),
)

data class VoiceUiState(
    val listening: Boolean = false,
    val partialText: String = "",
    val finalText: String = "",
    val language: String = "普通话",
    val message: String = "",
)
