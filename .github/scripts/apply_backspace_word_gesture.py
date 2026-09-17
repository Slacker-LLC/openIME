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


decorator_path = Path("app/src/main/java/llc/slacker/openime/NineKeySymbolRailDecorator.kt")
decorator = decorator_path.read_text()
decorator = replace_once(
    decorator,
    '''        installPinyin26LongPressDigits(root, onCommit)
        decorateGestureDescriptions(root)
''',
    '''        installPinyin26LongPressDigits(root, onCommit)
        installBackspaceGestures(root)
        decorateGestureDescriptions(root)
''',
    "decorate backspace hook",
)
marker = '    private fun decorateGestureDescriptions(root: View) {\n'
if marker not in decorator:
    raise SystemExit("decorateGestureDescriptions marker missing")
function = r'''    /**
     * Production backspace gesture: tap deletes one code point, hold repeats,
     * swipe up clears all, and swipe left deletes the previous word/token.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installBackspaceGestures(root: View) {
        val key = root.findViewWithTag<ImeKeyView>("key-backspace") ?: return
        val listener = root.context as? ImeKeyboardViewV2.Listener ?: return
        val context = root.context
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val armDistance = dp(context, 36).toFloat()
        val earlyMove = dp(context, 8).toFloat()
        val verticalTolerance = dp(context, 64).toFloat()
        val horizontalTolerance = dp(context, 96).toFloat()
        val ACTION_NONE = 0
        val ACTION_DELETE_WORD = 1
        val ACTION_CLEAR_ALL = 2

        var downX = 0f
        var downY = 0f
        var armedAction = ACTION_NONE
        var moved = false
        var repeating = false

        val repeatDelete = object : Runnable {
            override fun run() {
                if (!key.isPressed || armedAction != ACTION_NONE || moved) return
                repeating = true
                listener.onBackspace()
                key.postDelayed(this, 60L)
            }
        }

        fun setArmed(next: Int) {
            if (armedAction == next) return
            armedAction = next
            key.removeCallbacks(repeatDelete)
            hideAlternatePreview(root)
            when (next) {
                ACTION_DELETE_WORD -> showAlternatePreview(root, key, "删词", tall = false)
                ACTION_CLEAR_ALL -> showAlternatePreview(root, key, "清空", tall = false)
            }
            if (ImeSettingsRepository.loadHaptic(context)) {
                key.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        key.setOnLongClickListener(null)
        key.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    key.removeCallbacks(repeatDelete)
                    downX = event.x
                    downY = event.y
                    armedAction = ACTION_NONE
                    moved = false
                    repeating = false
                    key.isPressed = true
                    key.parent?.requestDisallowInterceptTouchEvent(true)
                    playKeyFeedback(key)
                    key.postDelayed(repeatDelete, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val left = downX - event.x
                    val up = downY - event.y
                    val absX = abs(event.x - downX)
                    val absY = abs(event.y - downY)
                    if (absX > touchSlop || absY > touchSlop) moved = true
                    if (absX >= earlyMove || absY >= earlyMove) key.removeCallbacks(repeatDelete)

                    val next = when {
                        up >= armDistance && absX <= horizontalTolerance && up >= left.coerceAtLeast(0f) -> ACTION_CLEAR_ALL
                        left >= armDistance && absY <= verticalTolerance -> ACTION_DELETE_WORD
                        else -> ACTION_NONE
                    }
                    setArmed(next)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    key.removeCallbacks(repeatDelete)
                    key.isPressed = false
                    key.parent?.requestDisallowInterceptTouchEvent(false)
                    hideAlternatePreview(root)
                    when (armedAction) {
                        ACTION_DELETE_WORD -> listener.onTextEdit("delete-word")
                        ACTION_CLEAR_ALL -> listener.onClearAll()
                        else -> if (!moved && !repeating) listener.onBackspace()
                    }
                    armedAction = ACTION_NONE
                    moved = false
                    repeating = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    key.removeCallbacks(repeatDelete)
                    key.isPressed = false
                    key.parent?.requestDisallowInterceptTouchEvent(false)
                    hideAlternatePreview(root)
                    armedAction = ACTION_NONE
                    moved = false
                    repeating = false
                    true
                }

                else -> true
            }
        }
    }

'''
decorator = decorator.replace(marker, function + marker, 1)
decorator_path.write_text(decorator)
