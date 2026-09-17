from pathlib import Path

path = Path("app/src/main/java/llc/slacker/openime/ImeKeyboardView.kt")
text = path.read_text()
old = '''    // Voice arms at the platform long-press threshold instead of a hard-coded
    // 150 ms, so a deliberate-but-brief space press no longer opens the mic.
    private val spaceVoiceTriggerMs = ViewConfiguration.getLongPressTimeout().toLong()
'''
new = '''    // Voice long-press intentionally arms at 150 ms for low-latency dictation.
    private val spaceVoiceTriggerMs = 150L
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"voice threshold block: expected 1 match, got {count}")
path.write_text(text.replace(old, new, 1))
