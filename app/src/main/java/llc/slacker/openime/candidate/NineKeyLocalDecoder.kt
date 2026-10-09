package llc.slacker.openime.candidate

import llc.slacker.openime.core.ImeData
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Fast, frequency-aware local fallback for Chinese 9-key.
 *
 * Rime is authoritative once its async result arrives. This decoder exists so
 * the first frame is still useful while librime starts/queries, without scanning
 * every entry in the same first-digit bucket on every key press.
 */
internal class NineKeyLocalDecoder(
    private val engine: CandidateEngine,
) {
    data class Resolution(
        val previewSuffix: String,
        val pinyinSuffixes: List<String>,
        val candidates: List<String>,
    )

    private data class Entry(
        val pinyin: String,
        val digits: String,
        val candidates: List<String>,
        val phrase: Boolean,
        val weight: Int,
    )

    private data class Decode(
        val pinyin: String,
        val text: String,
        val score: Int,
        val parts: Int,
    )

    private class TrieNode {
        val children = HashMap<Char, TrieNode>()
        val exact = ArrayList<Entry>()
        var bestDescendant: Entry? = null
    }

    /**
     * One way to read the start of the open digits as pinyin. [coversAll] tells
     * whether the syllable spells every open digit or only the first of them.
     */
    data class Reading(
        val syllables: List<String>,
        val coversAll: Boolean,
        /** False for a bare initial (w, x, y, z): a letter to show, not a syllable to fix. */
        val complete: Boolean = true,
    ) {
        val display: String get() = syllables.joinToString("'")
    }

    private data class SyllablePath(val syllables: List<String>, val score: Int)

    private val root = TrieNode()

    /** Concatenated pinyin of known words -> how good they are (rewards readings that form words). */
    private val phraseScores = HashMap<String, Int>()

    /** Every digit prefix of every syllable: "an unfinished last syllable" test. */
    private val syllablePrefixes = HashSet<String>()
    private val completeSyllables = HashSet<String>()

    /** Digit prefix of a syllable -> the letters of the likeliest syllable it starts, and that syllable's score. */
    private val prefixSpellings = HashMap<String, Pair<String, Int>>()

    /** Han character -> syllables that can read it, most likely first. */
    private val readingsByChar = HashMap<String, List<String>>()
    private var previousDigits = ""
    private var previousPreview = ""

    init {
        val entries = buildEntries()
        entries.forEach(::insert)
        sortTrie(root)
        buildReadings(entries)
        entries.forEach { entry ->
            if (entry.phrase && entry.pinyin.all { it in 'a'..'z' }) {
                phraseScores.merge(entry.pinyin, entryScore(entry), ::maxOf)
            }
            if (isSyllableEntry(entry)) {
                completeSyllables += entry.pinyin
                val score = entryScore(entry)
                for (length in 1..entry.digits.length) {
                    val prefix = entry.digits.substring(0, length)
                    syllablePrefixes += prefix
                    val known = prefixSpellings[prefix]
                    if (known == null || score > known.second) prefixSpellings[prefix] = entry.pinyin.substring(0, length) to score
                }
            }
        }
    }

    @Synchronized
    fun resolve(
        digits: String,
        preferredSuffix: String?,
        fuzzy: Boolean,
    ): Resolution {
        val bounded = digits.filter { it in '2'..'9' }.take(MAX_DIGITS)
        if (bounded.isEmpty()) {
            previousDigits = ""
            previousPreview = ""
            return Resolution("", emptyList(), emptyList())
        }

        val node = nodeFor(bounded)
        val exactEntries = node?.exact.orEmpty().take(MAX_PATHS)
        val decoded = decodePaths(bounded)
        val preferred = preferredSuffix
            ?.lowercase()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val preferredDigits = preferred?.let(::digitsForPinyin)
        val stable = preferred?.takeIf { preferredDigits == bounded }

        val validPreset = NineKeyPresets.combinations[bounded]
            .orEmpty()
            .filter { digitsForPinyin(it) == bounded }

        val rankedPool = buildList {
            addAll(validPreset)
            addAll(exactEntries.map { it.pinyin })
            addAll(decoded.map { it.pinyin })
        }
            .filter { it.isNotBlank() }
            .distinct()

        val continuationBase = when {
            preferred != null &&
                preferredDigits != null &&
                bounded.length > preferredDigits.length &&
                bounded.startsWith(preferredDigits) -> preferred
            previousDigits.isNotEmpty() &&
                bounded.length > previousDigits.length &&
                bounded.startsWith(previousDigits) &&
                previousPreview.isNotEmpty() -> previousPreview
            else -> null
        }
        // A one-key prefix can choose a plausible but wrong path (for example
        // 6 -> mi). Once the complete digit stream matches a high-confidence
        // preset, let that preset re-rank the preview instead of carrying the
        // provisional prefix forever. Explicit choices still win through
        // `stable` above.
        val continuous = continuationBase
            ?.takeIf { validPreset.isEmpty() }
            ?.let { base ->
                rankedPool.firstOrNull { candidate ->
                    candidate.startsWith(base) && digitsForPinyin(candidate) == bounded
                }
            }

        val paths = buildList {
            stable?.let(::add)
            continuous?.let(::add)
            addAll(rankedPool)
        }
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_PATHS)
            .toList()

        val batches = ArrayList<List<String>>()
        exactEntries.forEach { entry ->
            val clean = entry.candidates.filter(::isDisplayCandidate).take(PER_PATH_CANDIDATES)
            if (clean.isNotEmpty()) batches += clean
        }
        decoded.forEach { state ->
            if (state.text.isNotBlank()) batches += listOf(state.text)
        }
        paths.forEach { path ->
            val clean = engine.getCandidates(path, fuzzy)
                .asSequence()
                .filter(::isDisplayCandidate)
                .filter { it != path }
                .take(PER_PATH_CANDIDATES)
                .toList()
            if (clean.isNotEmpty()) batches += clean
        }

        // A prefix that is not yet a complete dictionary spelling must still
        // look like the path the user is building. For an explicit/continuous
        // path choice, keep that descendant ahead of an automatically decoded
        // segmented path. Future words remain preview-only until their full
        // digit spelling has actually been typed.
        val preferredDescendant = continuationBase?.let { base ->
            bestDescendantStartingWith(node, base)
        }
        val prefixPreview = (preferredDescendant ?: node?.bestDescendant)
            ?.pinyin
            ?.take(bounded.length)
            ?.takeIf { digitsForPinyin(it) == bounded }
        // The pre-edit is always letters: when no word or path decides them,
        // spellDigits reads whole syllables and the start of an unfinished one
        // (6442646 -> ni hao go). Showing the digits instead left "669" or a
        // whole digit string in the pre-edit until (or after) Rime answered.
        val preview = when {
            stable != null -> stable
            continuous != null -> continuous
            bounded.length == 1 -> spellDigits(bounded).joinToString(" ")
            paths.isEmpty() -> prefixPreview ?: spellDigits(bounded).joinToString(" ")
            continuationBase != null && validPreset.isEmpty() && prefixPreview != null -> prefixPreview
            paths.isNotEmpty() -> paths.first()
            prefixPreview != null -> prefixPreview
            else -> spellDigits(bounded).joinToString(" ")
        }

        previousDigits = bounded
        previousPreview = preview
        return Resolution(
            previewSuffix = preview,
            pinyinSuffixes = paths,
            candidates = roundRobin(batches, MAX_CANDIDATES),
        )
    }

    private fun buildReadings(entries: List<Entry>) {
        val byChar = HashMap<String, MutableList<Entry>>()
        entries.forEach { entry ->
            if (entry.phrase || entry.pinyin.length > MAX_SYLLABLE_LENGTH ||
                !entry.pinyin.all { it in 'a'..'z' }
            ) {
                return@forEach
            }
            entry.candidates.forEach { value ->
                if (value.codePointCount(0, value.length) == 1) {
                    byChar.getOrPut(value) { ArrayList(2) } += entry
                }
            }
        }
        byChar.forEach { (char, list) ->
            readingsByChar[char] = list
                .sortedWith(compareByDescending<Entry>(::entryScore).thenBy { it.pinyin })
                .map { it.pinyin }
                .distinct()
        }
    }

    /**
     * Pinyin of [text] when it exactly spells the whole digit string, one
     * syllable per character, or null. Used to make the visible pinyin agree
     * with the word Rime ranks first.
     */
    @Synchronized
    fun readingFor(digits: String, text: String): List<String>? {
        val bounded = digits.filter { it in '2'..'9' }
        if (bounded.isEmpty() || text.isEmpty()) return null
        val chars = ArrayList<String>()
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            chars += String(Character.toChars(cp))
            index += Character.charCount(cp)
        }
        if (chars.size > bounded.length) return null

        val picked = arrayOfNulls<String>(chars.size)
        fun walk(charIndex: Int, digitIndex: Int): Boolean {
            if (charIndex == chars.size) return digitIndex == bounded.length
            for (reading in readingsByChar[chars[charIndex]].orEmpty()) {
                val code = digitsForPinyin(reading) ?: continue
                if (!bounded.startsWith(code, digitIndex)) continue
                picked[charIndex] = reading
                if (walk(charIndex + 1, digitIndex + code.length)) return true
            }
            return false
        }
        if (walk(0, 0)) return picked.filterNotNull()
        // One key and one character: the key can only be the character's
        // initial (4 -> 和 shows "h", like the rail). Without this the pre-edit
        // stayed on the bare digit for keys whose letters are all initials
        // (4, 5, 7, 8, 9) while 2 / 3 / 6 showed a / e / o.
        if (bounded.length == 1 && chars.size == 1) {
            val initial = readingsByChar[chars[0]].orEmpty()
                .map { it.take(1) }
                .firstOrNull { digitsForPinyin(it) == bounded }
            if (initial != null) return listOf(initial)
        }
        return null
    }

    /**
     * The pre-edit for a candidate that spells only the start of the digits,
     * the usual case in long input: its readings, the last one possibly cut
     * where the digits end (你好工 for 6442646 -> ni hao go), then
     * [spellDigits] for anything after the word. Null when the candidate does
     * not fit the digits at all.
     */
    @Synchronized
    fun readingForPrefix(digits: String, text: String): List<String>? {
        val bounded = digits.filter { it in '2'..'9' }
        if (bounded.isEmpty() || text.isEmpty()) return null
        readingFor(bounded, text)?.let { return it }
        val chars = ArrayList<String>()
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            chars += String(Character.toChars(cp))
            index += Character.charCount(cp)
        }
        // Each character by its full reading, else by the start of one: Rime's
        // nine-key matches initials (669 -> 模型 as mo'x) and its key
        // corrections accept cut syllables (go for gong). Take the characters,
        // in order, as far as they fit the digits; the rest is spelled.
        data class Walk(val parts: List<String>, val full: Int)
        var states = mapOf(0 to Walk(emptyList(), 0))
        var bestEnd = 0
        var best = Walk(emptyList(), 0)
        for (char in chars) {
            val next = HashMap<Int, Walk>()
            for ((digitIndex, walk) in states) {
                if (digitIndex == bounded.length) continue
                for (reading in readingsByChar[char].orEmpty()) {
                    val code = digitsForPinyin(reading) ?: continue
                    for (length in code.length downTo 1) {
                        if (digitIndex + length > bounded.length) continue
                        if (!bounded.startsWith(code.substring(0, length), digitIndex)) continue
                        val end = digitIndex + length
                        val step = Walk(walk.parts + reading.take(length), walk.full + if (length == code.length) 1 else 0)
                        val known = next[end]
                        if (known == null || step.full > known.full) next[end] = step
                    }
                }
            }
            if (next.isEmpty()) break
            next.forEach { (end, walk) ->
                if (end > bestEnd || (end == bestEnd && walk.full > best.full)) {
                    bestEnd = end
                    best = walk
                }
            }
            states = next
        }
        if (best.parts.isEmpty()) return null
        return if (bestEnd == bounded.length) best.parts else best.parts + spellDigits(bounded.substring(bestEnd))
    }

    /**
     * Letters for [digits], one per digit, when no word decides them: the
     * fewest whole syllables, the likeliest start of one for an unfinished
     * end, and a key's first letter only where nothing else reads. Never the
     * digits themselves.
     */
    @Synchronized
    fun spellDigits(digits: String): List<String> {
        val bounded = digits.filter { it in '2'..'9' }
        val n = bounded.length
        if (n == 0) return emptyList()
        // A part is a whole syllable, or the start of one: natural at the end
        // (the syllable is still being typed), a last resort in the middle. Fewer
        // and whole parts win (a lone a / o / e between syllables counts double,
        // a grid artefact as in ni'ha'o); then likelier syllables and words.
        data class Spelling(val parts: List<String>, val cost: Int, val score: Int, val partialEnd: Boolean = false)
        val better = compareBy<Spelling> { it.cost }.thenBy { it.partialEnd }.thenByDescending { it.score }
        val states = Array(n + 1) { ArrayList<Spelling>() }
        states[0] += Spelling(emptyList(), 0, 0)
        for (start in 0 until n) {
            val from = states[start].sortedWith(better).take(SPELL_BEAM)
            if (from.isEmpty()) continue
            var node = root
            for (end in start until minOf(n, start + MAX_SYLLABLE_LENGTH)) {
                node = node.children[bounded[end]] ?: break
                val chunk = bounded.substring(start, end + 1)
                val whole = node.exact.filter(::isSyllableEntry).sortedByDescending(::entryScore).take(SPELL_BRANCHES)
                for (before in from) {
                    for (entry in whole) {
                        val parts = before.parts + entry.pinyin
                        val cost = if (entry.pinyin.length == 1 && n > 1) 2 else 1
                        states[end + 1] += Spelling(parts, before.cost + cost, before.score + entryScore(entry) + phraseBonus(parts))
                    }
                    val (letters, score) = prefixSpellings[chunk] ?: continue
                    if (whole.any { it.pinyin == letters }) continue
                    val last = end + 1 == n
                    states[end + 1] += Spelling(before.parts + letters, before.cost + if (last) 1 else 3, before.score + score / 2, partialEnd = last)
                }
            }
        }
        states[n].minWithOrNull(better)?.let { return it.parts }
        // Unreachable while every key starts some syllable; keep the letters promise anyway.
        return listOf(bounded.map { KEY_LETTERS.getValue(it).first() }.joinToString(""))
    }

    /**
     * The readings offered in the left rail, best first: one syllable per item,
     * for the next character only (`ni`, `mi`, ...). Fixing one moves the list on
     * to the following character, the way Baidu and rime-t9-shiyin do it, so the
     * user chooses the pinyin of one character at a time and never has to pick
     * a whole phrase's spelling. A tap fixes exactly what the item shows, and
     * choices that would leave digits no syllable can read are never offered.
     */
    @Synchronized
    fun readingOptions(digits: String, preferred: String?, limit: Int = MAX_SYLLABLE_OPTIONS): List<Reading> {
        val bounded = digits.filter { it in '2'..'9' }.take(MAX_DIGITS)
        if (bounded.isEmpty()) return emptyList()
        val lead = preferred?.lowercase()?.filter { it in 'a'..'z' }.orEmpty()

        // A lone a / o / e between syllables is almost always an artefact of the
        // digit grid (ni'ha'o), not what anyone typed; keep such readings only
        // when nothing else exists.
        val all = syllablePaths(bounded, READING_BEAM)
        val paths = all.filter { path -> path.syllables.none { it.length == 1 } }.ifEmpty { all }

        // First syllables, each only if the rest can still be read.
        val firsts = LinkedHashMap<String, Int>()
        // Rank by the path's score per syllable: a plain sum let any two-syllable
        // reading outrank a one-syllable one (xia before xiang, qi before qian).
        paths.forEach { path ->
            val first = path.syllables.first()
            if (first !in RAIL_DUPLICATES) firsts.putIfAbsent(first, path.score / path.syllables.size)
        }
        syllableOptions(bounded, preferred, limit * 2).forEach { syllable ->
            if (syllable.length > 1 && syllable !in completeSyllables) return@forEach
            if (syllable in RAIL_DUPLICATES) return@forEach
            val code = digitsForPinyin(syllable) ?: return@forEach
            if (code.length == bounded.length || canRead(bounded.substring(code.length))) {
                firsts.putIfAbsent(syllable, Int.MIN_VALUE)
            }
        }
        val ordered = firsts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { lead.startsWith(it.key) && it.key == leadFirst(lead, firsts.keys) }
                    .thenByDescending { it.value },
            )
            .map { it.key }
        return ordered.take(limit).map { syllable ->
            Reading(
                listOf(syllable),
                coversAll = digitsForPinyin(syllable)?.length == bounded.length,
                complete = syllable in completeSyllables,
            )
        }
    }

    /** The longest listed syllable that the preview starts with. */
    private fun leadFirst(lead: String, candidates: Set<String>): String? =
        candidates.filter { lead.startsWith(it) }.maxByOrNull { it.length }

    /** Whether [digits] can be read as real syllables, the last one possibly unfinished. */
    private fun canRead(digits: String): Boolean {
        if (digits.isEmpty()) return true
        val reachable = BooleanArray(digits.length + 1).also { it[0] = true }
        for (start in digits.indices) {
            if (!reachable[start]) continue
            if (digits.substring(start) in syllablePrefixes) return true
            var node = root
            for (end in start until minOf(digits.length, start + MAX_SYLLABLE_LENGTH)) {
                node = node.children[digits[end]] ?: break
                if (node.exact.any(::isSyllableEntry)) reachable[end + 1] = true
            }
        }
        return reachable[digits.length]
    }

    /** A real syllable: letters only, short, and with a vowel (no `ng`, `m`, `hm` interjections). */
    private fun isSyllableEntry(entry: Entry): Boolean =
        !entry.phrase &&
            entry.pinyin.length <= MAX_SYLLABLE_LENGTH &&
            entry.pinyin.all { it in 'a'..'z' } &&
            entry.pinyin.any { it in "aeiouv" }

    /**
     * Every way to read [digits] as whole syllables, best first. A beam keeps
     * long input cheap; a reading that forms a known word (`nihao`) outranks
     * unrelated syllables, so `ni'hao` leads `mi'hao` and `ni'gao`.
     */
    private fun syllablePaths(digits: String, beam: Int): List<SyllablePath> {
        val n = digits.length
        val states = Array(n + 1) { ArrayList<SyllablePath>() }
        states[0] += SyllablePath(emptyList(), 0)
        for (start in 0 until n) {
            val from = states[start]
            if (from.isEmpty()) continue
            var node = root
            for (end in start until minOf(n, start + MAX_SYLLABLE_LENGTH)) {
                node = node.children[digits[end]] ?: break
                val syllables = node.exact.filter(::isSyllableEntry)
                if (syllables.isEmpty()) continue
                val target = states[end + 1]
                for (entry in syllables) {
                    val own = entryScore(entry) - PART_PENALTY
                    for (before in from) {
                        val next = before.syllables + entry.pinyin
                        target += SyllablePath(next, before.score + own + phraseBonus(next))
                    }
                }
                if (target.size > beam * 2) {
                    val kept = target
                        .sortedByDescending { it.score }
                        .distinctBy { it.syllables }
                        .take(beam)
                    target.clear()
                    target.addAll(kept)
                }
            }
        }
        return states[n]
            .sortedByDescending { it.score }
            .distinctBy { it.syllables }
            .take(beam)
    }

    /** Bonus for the words the newest syllable completes (longest suffixes of the path). */
    private fun phraseBonus(path: List<String>): Int {
        var bonus = 0
        for (length in 2..minOf(MAX_PHRASE_SYLLABLES, path.size)) {
            val key = path.takeLast(length).joinToString("")
            bonus += phraseScores[key] ?: continue
        }
        return bonus
    }

    /**
     * Real Pinyin syllables that can start the open digit tail, for the left
     * selection rail. Longer spellings come first (they consume more of the
     * typed digits), the syllable the preview currently starts with leads, and
     * ties follow corpus frequency. A lone digit also offers its key letters so
     * a key such as 9 is never a dead end.
     */
    @Synchronized
    fun syllableOptions(digits: String, preferred: String?, limit: Int = MAX_SYLLABLE_OPTIONS): List<String> {
        val bounded = digits.filter { it in '2'..'9' }.take(MAX_DIGITS)
        if (bounded.isEmpty()) return emptyList()
        val lead = preferred?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }
        data class Option(val pinyin: String, val depth: Int, val score: Int)
        val found = ArrayList<Option>()
        var node = root
        for (depth in 1..minOf(bounded.length, MAX_SYLLABLE_LENGTH)) {
            node = node.children[bounded[depth - 1]] ?: break
            node.exact.forEach { entry ->
                if (isSyllableEntry(entry) && entry.pinyin.length == depth) {
                    found += Option(entry.pinyin, depth, entryScore(entry))
                }
            }
        }
        // The syllable the preview starts with leads, so the highlighted
        // choice always agrees with the pinyin the user is looking at.
        val leadPinyin = lead?.let { text ->
            found.filter { text.startsWith(it.pinyin) }.maxByOrNull { it.depth }?.pinyin
        }
        val ordered = found
            .sortedWith(
                compareByDescending<Option> { it.pinyin == leadPinyin }
                    .thenByDescending { it.depth }
                    .thenByDescending { it.score }
                    .thenBy { it.pinyin },
            )
            .map { it.pinyin }
            .distinct()
            .toMutableList()
        if (bounded.length == 1) {
            ImeData.keypad9Map[bounded]
                .orEmpty()
                .filter { it.length == 1 && it[0] in 'a'..'z' }
                // A bare letter no syllable starts with (i, u, v) is a dead end.
                .filter { letter -> completeSyllables.any { it.startsWith(letter) } }
                .forEach { if (it !in ordered) ordered += it }
        }
        return ordered.take(limit)
    }

    private fun buildEntries(): List<Entry> {
        val merged = LinkedHashMap<String, MutableList<String>>()
        ImeData.phraseDict.forEach { (pinyin, values) ->
            merged.getOrPut(pinyin) { mutableListOf() }.addAll(values)
        }
        // Put the compact common-character table ahead of the generated Unihan
        // coverage table. The latter is not frequency ordered.
        ImeData.pinyinDict.forEach { (pinyin, values) ->
            merged.getOrPut(pinyin) { mutableListOf() }.addAll(values)
        }
        PinyinLexicon.current().forEach { (pinyin, values) ->
            merged.getOrPut(pinyin) { mutableListOf() }.addAll(values)
        }

        return merged.flatMap { (pinyin, rawValues) ->
            val digits = digitsForPinyin(pinyin) ?: return@flatMap emptyList<Entry>()
            val values = rawValues.distinct().take(MAX_CANDIDATES)
            if (values.isEmpty()) return@flatMap emptyList<Entry>()
            fun entryOf(list: List<String>, phrase: Boolean) = Entry(
                pinyin = pinyin,
                digits = digits,
                candidates = list,
                phrase = phrase,
                weight = list.maxOfOrNull { value -> PinyinLexicon.weightFor(pinyin, value) } ?: 0,
            )
            val singles = values.filter { it.codePointCount(0, it.length) == 1 }
            val multis = values.filter { it.codePointCount(0, it.length) > 1 }
            if (singles.isNotEmpty() && multis.isNotEmpty()) {
                // A syllable that is also a word's spelling (xian / 西安, liu / 浏览, pin / 拼音)
                // is still a syllable: keep its characters apart from the words.
                listOf(entryOf(singles, phrase = false), entryOf(multis, phrase = true))
            } else {
                listOf(entryOf(values, phrase = pinyin in ImeData.phraseDict || multis.isNotEmpty()))
            }
        }
    }

    private fun insert(entry: Entry) {
        var node = root
        entry.digits.forEach { digit ->
            node = node.children.getOrPut(digit) { TrieNode() }
        }
        node.exact += entry
    }

    /** Sort exact entries and cache the best scoring entry reachable below each prefix. */
    private fun sortTrie(node: TrieNode): Entry? {
        node.exact.sortWith(
            compareByDescending<Entry>(::entryScore)
                .thenBy { it.pinyin },
        )
        val childBest = node.children.values.mapNotNull(::sortTrie)
        node.bestDescendant = (node.exact.asSequence() + childBest.asSequence())
            .maxWithOrNull(
                compareBy<Entry>(::entryScore)
                    .thenByDescending { it.pinyin },
            )
        return node.bestDescendant
    }

    private fun bestDescendantStartingWith(node: TrieNode?, pinyinPrefix: String): Entry? {
        if (node == null) return null
        var best = node.exact
            .asSequence()
            .filter { it.pinyin.startsWith(pinyinPrefix) }
            .maxWithOrNull(compareBy<Entry>(::entryScore).thenByDescending { it.pinyin })
        node.children.values.forEach { child ->
            val candidate = bestDescendantStartingWith(child, pinyinPrefix) ?: return@forEach
            val current = best
            if (current == null || entryScore(candidate) > entryScore(current) ||
                (entryScore(candidate) == entryScore(current) && candidate.pinyin < current.pinyin)
            ) {
                best = candidate
            }
        }
        return best
    }

    private fun nodeFor(digits: String): TrieNode? {
        var node = root
        digits.forEach { digit ->
            node = node.children[digit] ?: return null
        }
        return node
    }

    /** Beam DP over the digit trie; boundaries are retained as spaces. */
    private fun decodePaths(digits: String): List<Decode> {
        val states = Array(digits.length + 1) { mutableListOf<Decode>() }
        states[0] += Decode(pinyin = "", text = "", score = 0, parts = 0)

        for (start in digits.indices) {
            val previous = states[start].take(MAX_BEAM)
            if (previous.isEmpty()) continue
            var node = root
            for (end in start until digits.length) {
                node = node.children[digits[end]] ?: break
                if (node.exact.isEmpty()) continue
                node.exact.take(MAX_BRANCHES).forEach { entry ->
                    previous.forEach { before ->
                        val candidate = Decode(
                            pinyin = if (before.pinyin.isEmpty()) {
                                entry.pinyin
                            } else {
                                before.pinyin + " " + entry.pinyin
                            },
                            text = before.text + entry.candidates.firstOrNull().orEmpty(),
                            score = before.score + entryScore(entry) - PART_PENALTY,
                            parts = before.parts + 1,
                        )
                        states[end + 1] += candidate
                    }
                }
                states[end + 1] = states[end + 1]
                    .distinctBy { it.pinyin to it.text }
                    .sortedWith(
                        compareByDescending<Decode> { it.score }
                            .thenBy { it.parts },
                    )
                    .take(MAX_BEAM)
                    .toMutableList()
            }
        }

        return states[digits.length]
            .filter { it.parts > 0 }
            .sortedWith(compareByDescending<Decode> { it.score }.thenBy { it.parts })
            .take(MAX_PATHS)
    }

    private fun entryScore(entry: Entry): Int {
        val corpus = if (entry.weight > 0) {
            (ln(entry.weight.toDouble() + 1.0) * 120.0).roundToInt()
        } else {
            0
        }
        val phraseBoost = if (entry.phrase) 420 else 0
        return corpus + phraseBoost + entry.pinyin.length * 4
    }

    private fun roundRobin(batches: List<List<String>>, limit: Int): List<String> {
        if (batches.isEmpty() || limit <= 0) return emptyList()
        // Batches are already ordered by path quality. Round-robin made the
        // first result of a weak path outrank every second result of the best
        // path, which produced surprising candidates for ambiguous digits.
        // Aggregate rank evidence instead, while retaining first-seen order as
        // a deterministic tie breaker.
        data class Ranked(val value: String, val score: Int, val order: Int)
        val scores = LinkedHashMap<String, Ranked>()
        var order = 0
        batches.forEachIndexed { batchIndex, batch ->
            batch.forEachIndexed { rank, value ->
                val score = (batches.size - batchIndex) * 100 - rank * 8
                val current = scores[value]
                if (current == null || score > current.score) {
                    scores[value] = Ranked(value, score, current?.order ?: order)
                }
                order++
            }
        }
        return scores.values
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.order })
            .take(limit)
            .map { it.value }
    }

    private fun isDisplayCandidate(value: String): Boolean =
        value.isNotBlank() && value.none(Char::isDigit) && value.any { it.code > 0x7f }

    companion object {
        const val MAX_DIGITS = 64
        const val MAX_SYLLABLE_OPTIONS = 12
        private const val READING_BEAM = 24
        private const val MAX_PHRASE_SYLLABLES = 6
        private const val MAX_SYLLABLE_LENGTH = 6
        private const val MAX_PATHS = 12
        private const val MAX_BEAM = 12
        private const val SPELL_BEAM = 8
        private const val SPELL_BRANCHES = 8
        private const val MAX_BRANCHES = 10
        private const val MAX_CANDIDATES = 96
        private const val PER_PATH_CANDIDATES = 24
        private const val PART_PENALTY = 55

        /** Spellings of lve / nve that the legacy table also holds; the rail shows one of each. */
        private val RAIL_DUPLICATES = setOf("lue", "nue")

        /** The letters printed on each nine-key digit. */
        private val KEY_LETTERS = mapOf(
            '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
            '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
        )

        fun digitsForPinyin(pinyin: String): String? {
            if (pinyin.isBlank()) return null
            val digits = StringBuilder(pinyin.length)
            pinyin.lowercase().forEach { ch ->
                val mapped = when (ch) {
                    in 'a'..'c' -> '2'
                    in 'd'..'f' -> '3'
                    in 'g'..'i' -> '4'
                    in 'j'..'l' -> '5'
                    in 'm'..'o' -> '6'
                    in 'p'..'s' -> '7'
                    in 't'..'v', 'ü' -> '8'
                    in 'w'..'z' -> '9'
                    ' ', '\'', '|' -> return null
                    else -> return null
                }
                digits.append(mapped)
            }
            return digits.toString().ifEmpty { null }
        }

        /**
         * Build the one authoritative Rime code for an explicitly segmented
         * composition. Letters in [segmentPrefix] are syllables the user has
         * already fixed (by tapping a Pinyin choice or the segment key); they
         * stay letters so Rime cannot re-interpret them as another spelling
         * with the same digits (zhong/xiong). The luna_pinyin schema accepts
         * letters and 2-9 digits in one input. [suffixDigits] is still
         * ambiguous and therefore stays digits. Without [lockLetters] every
         * prefix letter becomes its digit again (the legacy behaviour, used
         * where the prefix is only a display guess).
         */
        fun nativeCode(segmentPrefix: String, suffixDigits: String, lockLetters: Boolean = false): String? {
            val out = StringBuilder(segmentPrefix.length + suffixDigits.length)
            segmentPrefix.lowercase().forEach { ch ->
                when {
                    ch in 'a'..'z' || ch == 'ü' -> {
                        if (lockLetters) {
                            out.append(if (ch == 'ü') 'v' else ch)
                        } else {
                            out.append(digitsForPinyin(ch.toString()) ?: return null)
                        }
                    }
                    ch == '|' || ch == '\'' || ch.isWhitespace() -> {
                        if (out.isNotEmpty() && out.last() != '\'') out.append('\'')
                    }
                    else -> return null
                }
            }
            val digits = suffixDigits.filter { it in '2'..'9' }
            if (digits.isNotEmpty() && out.isNotEmpty() && out.last() != '\'') out.append('\'')
            out.append(digits)
            return out.toString().trim('\'').ifBlank { null }
        }
    }
}
