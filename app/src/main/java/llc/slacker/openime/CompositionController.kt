package llc.slacker.openime

/**
 * Pure composition state machine (unit-testable).
 *
 * Mirrors the design behavior:
 *  - pinyin letters accumulate
 *  - backspace removes the last letter
 *  - space/enter commit the first candidate
 *  - selecting a candidate clears composition
 */
class CompositionController(
    private val engine: CandidateEngine,
) {
    data class Snapshot(
        val composition: String = "",
        val candidates: List<String> = emptyList(),
    )

    private var snapshot = Snapshot()

    fun append(char: String, fuzzy: Boolean = false): Snapshot {
        val next = snapshot.composition + char
        return update(next, fuzzy)
    }

    fun backspace(fuzzy: Boolean = false): Snapshot {
        val next = snapshot.composition.dropLast(1)
        return update(next, fuzzy)
    }

    fun selectCandidate(candidate: String): Snapshot {
        snapshot = Snapshot()
        return snapshot
    }

    fun firstCandidate(): String? = snapshot.candidates.firstOrNull()

    fun clear(): Snapshot {
        snapshot = Snapshot()
        return snapshot
    }

    fun snapshot(): Snapshot = snapshot

    fun set9KeyNumber(digits: String): Snapshot {
        val result = engine.get9KeyCandidates(digits)
        snapshot = Snapshot(digits, result.candidates)
        return snapshot
    }

    fun setT9(digits: String): Snapshot {
        snapshot = Snapshot(digits, engine.getT9EnglishCandidates(digits))
        return snapshot
    }

    /** Replace the current composition wholesale (used by the render sync). */
    fun replace(next: String, fuzzy: Boolean = false): Snapshot {
        snapshot = Snapshot(next, engine.getCandidates(next, fuzzy))
        return snapshot
    }

    private fun update(next: String, fuzzy: Boolean): Snapshot {
        snapshot = Snapshot(next, engine.getCandidates(next, fuzzy))
        return snapshot
    }
}
