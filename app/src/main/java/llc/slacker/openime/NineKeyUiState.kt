package llc.slacker.openime

/**
 * Process-local bridge between the candidate pipeline and the thin 9-key UI.
 * It stores only ambiguity labels and one explicit user choice; candidate data
 * remains owned by CandidatePipeline/Rime.
 */
internal object NineKeyUiState {
    private const val MAX_CODES = 32

    private data class Options(
        val paths: List<String>,
        val segmentPrefix: String,
    )

    private val lock = Any()
    private val byCode = object : LinkedHashMap<String, Options>(MAX_CODES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Options>?): Boolean =
            size > MAX_CODES
    }
    private var selectedCode: String? = null
    private var selectedPath: String? = null

    fun remember(code: String?, paths: List<String>, segmentPrefix: String) {
        val key = code?.takeIf(::isNineKeyCode) ?: return
        val clean = paths.map(String::trim).filter(String::isNotEmpty).distinct().take(8)
        synchronized(lock) {
            if (clean.isEmpty()) byCode.remove(key) else byCode[key] = Options(clean, segmentPrefix)
            val oldCode = selectedCode
            if (oldCode != null && !key.startsWith(oldCode)) {
                selectedCode = null
                selectedPath = null
            }
        }
    }

    fun pathsFor(code: String?): List<String> {
        val key = code ?: return emptyList()
        return synchronized(lock) { byCode[key]?.paths.orEmpty() }
    }

    fun selectedPathFor(code: String?): String? {
        val key = code ?: return null
        return synchronized(lock) {
            selectedPath?.takeIf { selectedCode == key }
        }
    }

    fun select(code: String, path: String) {
        synchronized(lock) {
            val options = byCode[code] ?: return
            if (path !in options.paths) return
            selectedCode = code
            selectedPath = path
        }
    }

    /**
     * Continue a manual ambiguity choice while new digits extend the same code.
     * CandidatePipeline passes only the suffix to the local decoder because the
     * explicit segment prefix is already fixed by the 1/分词 key.
     */
    fun preferredSuffixFor(code: String?, segmentPrefix: String): String? {
        val key = code ?: return null
        return synchronized(lock) {
            val sourceCode = selectedCode ?: return@synchronized null
            val fullPath = selectedPath ?: return@synchronized null
            if (!key.startsWith(sourceCode)) return@synchronized null
            if (!fullPath.startsWith(segmentPrefix)) return@synchronized null
            fullPath.removePrefix(segmentPrefix).trimStart().ifBlank { null }
        }
    }

    fun clear() {
        synchronized(lock) {
            selectedCode = null
            selectedPath = null
        }
    }

    private fun isNineKeyCode(value: String): Boolean =
        value.isNotEmpty() && value.all { it in '2'..'9' || it == '\'' }
}
