package llc.slacker.openime.keyboard

/**
 * Keeps the autofill chips of the current response in the order the provider
 * sent them, even though each chip is inflated asynchronously.
 *
 * A new response (or [clear]) starts a new generation; a chip that arrives for
 * an older one is dropped, so a slow inflate can never put last field's
 * suggestion into this field's strip. [publish] always receives the chips that
 * are ready, in provider order, and an empty list when nothing is left.
 */
internal class InlineChipTracker<V : Any>(
    private val maxChips: Int,
    private val publish: (List<V>) -> Unit,
) {
    private var generation = 0
    private var slots: MutableList<V?> = mutableListOf()

    /** Starts a response of [count] suggestions; returns the token for its chips. */
    fun begin(count: Int): Int {
        generation++
        slots = MutableList(count.coerceIn(0, maxChips)) { null }
        if (slots.isEmpty()) publish(emptyList())
        return generation
    }

    /** Whether a response of [count] suggestions would be shown at all. */
    fun accepts(count: Int): Boolean = count > 0 && maxChips > 0

    /** A chip finished inflating; [chip] is null when the system could not build it. */
    fun deliver(token: Int, index: Int, chip: V?) {
        if (token != generation || index !in slots.indices || chip == null) return
        slots[index] = chip
        publish(slots.filterNotNull())
    }

    fun clear() {
        generation++
        slots = mutableListOf()
        publish(emptyList())
    }
}
