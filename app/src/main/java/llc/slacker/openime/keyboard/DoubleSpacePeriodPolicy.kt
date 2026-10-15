package llc.slacker.openime.keyboard

/**
 * 双击空格键输入句号: a second space inside the window turns the space that
 * was just written into 「。」.
 *
 * The reference ships the switch as `enable_double_click_space`
 * (`defaultValue=false`) on its 快捷格式输入 page (`res/xq.xml`, label
 * `string/double_click_space_to_input_period` 「双击空格键输入句号」). Its
 * handler (`InputViewRoot$z.invoke`, classes.dex code_off 0x4c1764) measures
 * `now - lastSpaceTime` against `0xc8` — **200 ms** — and calls
 * `KeyboardJni.commitForSpaceDoubleClick(!isPunct(charBeforeFirstSpace), true)`.
 * The punctuation gate keeps a doubled space after a mark as a plain space, so
 * 「， 」 stays a space instead of becoming 「，。」.
 */
internal object DoubleSpacePeriodPolicy {

    /** The window the reference compares against: `const-wide/16 0xc8`. */
    const val WINDOW_MS = 200L

    /** The period the second space becomes. */
    const val PERIOD = "。"

    /**
     * The reference's gate list, taken from the 31-element array built next to
     * the handler: space, ，。？！；：, both quote pairs, （）【】《》 and their
     * half-width forms ,.?!;:"'()[]<>. A space after any of these stays a space.
     */
    private const val PUNCTUATION = " ，。？！；：“”‘’（）【】《》,.?!;:\"'()[]<>"

    /**
     * True when the space completed at [previousSpaceAtMs] is [elapsedMs] old or
     * younger and the character before it was not punctuation. [charBeforeSpace]
     * is read before the first space is committed; null (unreadable editor, or
     * the field's start) leaves the space alone, as does an unset timestamp.
     */
    fun converts(elapsedMs: Long, charBeforeSpace: Char?): Boolean {
        if (elapsedMs > WINDOW_MS) return false
        if (elapsedMs < 0) return false
        if (charBeforeSpace == null) return false
        return charBeforeSpace !in PUNCTUATION
    }
}
