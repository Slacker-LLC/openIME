from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)


service_path = Path("app/src/main/java/llc/slacker/openime/LocalVoiceImeService.kt")
service = service_path.read_text()
service = replace_once(
    service,
    '        if (action in setOf("select-all", "cut", "paste", "left", "right")) prepareForManualInput()\n',
    '        if (action in setOf("select-all", "cut", "paste", "left", "right", "delete-word")) prepareForManualInput()\n',
    "onTextEdit prepareForManualInput",
)
service = replace_once(
    service,
    '''            "left" -> {
                gateway.moveCursorHorizontally(-1)
            }
''',
    '''            "delete-word" -> {
                noteVoiceBackspace()
                keyboardView?.clearAssociationCandidates()
                if (lastComposition.isNotEmpty()) {
                    gateway.cancelComposing()
                    clearImeCompositionState(render = true)
                } else {
                    gateway.deletePreviousWord()
                    clearImeCompositionState(render = true)
                }
            }
            "left" -> {
                gateway.moveCursorHorizontally(-1)
            }
''',
    "onTextEdit delete-word action",
)
service_path.write_text(service)


view_path = Path("app/src/main/java/llc/slacker/openime/ImeKeyboardView.kt")
view = view_path.read_text()
view = replace_once(
    view,
    "    private var backspaceClearArmed = false\n",
    "    private var backspaceClearArmed = false\n    private var backspaceDeleteWordArmed = false\n",
    "backspace delete-word state",
)
view = replace_once(
    view,
    "        backspaceClearArmed = false\n        backspaceRepeatStarted = false\n",
    "        backspaceClearArmed = false\n        backspaceDeleteWordArmed = false\n        backspaceRepeatStarted = false\n",
    "backspace begin reset",
)
view = replace_once(
    view,
    "            if (backspaceGestureActive && !backspaceClearArmed) repeatAction.run()\n",
    "            if (backspaceGestureActive && !backspaceClearArmed && !backspaceDeleteWordArmed) repeatAction.run()\n",
    "backspace repeat guard",
)

update_start = view.index("    private fun updateBackspaceGesture(rawX: Float, rawY: Float) {\n")
finish_start = view.index("    private fun finishBackspaceGesture(commit: Boolean) {\n", update_start)
new_update = '''    private fun updateBackspaceGesture(rawX: Float, rawY: Float) {
        if (!backspaceGestureActive) return
        val upward = backspaceStartY - rawY
        val leftward = backspaceStartX - rawX
        val horizontal = kotlin.math.abs(rawX - backspaceStartX)
        val vertical = kotlin.math.abs(rawY - backspaceStartY)

        // Once the motion clearly becomes directional, pause repeat-delete while
        // the gesture is deciding between swipe-up clear and swipe-left delete-word.
        val directionalIntent =
            (upward >= dp(8) && horizontal <= dp(96)) ||
                (leftward >= dp(8) && vertical <= dp(64))
        if (directionalIntent) {
            backspaceRepeatSuspended = true
            backspaceRepeatStartAction?.let(repeatHandler::removeCallbacks)
            repeatHandler.removeCallbacks(repeatAction)
        } else if (backspaceRepeatSuspended) {
            backspaceRepeatSuspended = false
            backspaceRepeatStartAction?.let {
                repeatHandler.postDelayed(
                    it,
                    if (backspaceRepeatStarted) 60L else ViewConfiguration.getLongPressTimeout().toLong(),
                )
            }
        }

        val clearDominates = upward >= leftward.coerceAtLeast(0f)
        val shouldArmClear = if (backspaceClearArmed) {
            upward > dp(16) && horizontal <= dp(120) && clearDominates
        } else {
            upward >= dp(36) && horizontal <= dp(96) && clearDominates
        }
        val shouldArmDeleteWord = if (shouldArmClear) {
            false
        } else if (backspaceDeleteWordArmed) {
            leftward > dp(16) && vertical <= dp(80)
        } else {
            leftward >= dp(36) && vertical <= dp(64) && leftward > upward.coerceAtLeast(0f)
        }

        if (shouldArmClear == backspaceClearArmed && shouldArmDeleteWord == backspaceDeleteWordArmed) return
        backspaceClearArmed = shouldArmClear
        backspaceDeleteWordArmed = shouldArmDeleteWord
        backspaceClearUiAction?.invoke(shouldArmClear)
        backspaceRepeatStartAction?.let(repeatHandler::removeCallbacks)
        repeatHandler.removeCallbacks(repeatAction)
        when {
            shouldArmClear -> backspaceAnchor?.let { showPopup(it, "清空") }
            shouldArmDeleteWord -> backspaceAnchor?.let { showPopup(it, "删词") }
            else -> hidePopup()
        }
        repeatHandler.removeCallbacks(popupHideRunnable)
        // Confirm both arming and disarming so the directional tier is tangible.
        hapticFeedback()
    }

'''
view = view[:update_start] + new_update + view[finish_start:]

finish_start = view.index("    private fun finishBackspaceGesture(commit: Boolean) {\n")
backspace_key_start = view.index("    private fun backspaceKey(): ImeKeyView", finish_start)
new_finish = '''    private fun finishBackspaceGesture(commit: Boolean) {
        if (!backspaceGestureActive) return
        val clearAll = commit && backspaceClearArmed
        val deleteWord = commit && !backspaceClearArmed && backspaceDeleteWordArmed
        val deleteOnce = commit && !backspaceClearArmed && !backspaceDeleteWordArmed && !backspaceRepeatStarted
        repeatHandler.removeCallbacks(repeatAction)
        backspaceRepeatStartAction?.let(repeatHandler::removeCallbacks)
        backspaceRepeatStartAction = null
        backspaceAnchor?.apply {
            isPressed = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        backspaceGestureActive = false
        backspaceClearUiAction?.invoke(false)
        backspaceClearArmed = false
        backspaceDeleteWordArmed = false
        backspaceRepeatStarted = false
        backspaceAnchor = null
        backspaceClearUiAction = null
        hidePopup()
        when {
            clearAll -> {
                hapticFeedback()
                listener.onClearAll()
            }
            deleteWord -> {
                hapticFeedback()
                listener.onTextEdit("delete-word")
            }
            deleteOnce -> {
                hidePopup()
                performBackspaceOnce()
            }
            else -> hidePopup()
        }
    }

'''
view = view[:finish_start] + new_finish + view[backspace_key_start:]
view = replace_once(
    view,
    '        contentDescription = "删除，向上滑清空"\n',
    '        contentDescription = "删除，向左滑删词，向上滑清空"\n',
    "backspace accessibility description",
)
view_path.write_text(view)
