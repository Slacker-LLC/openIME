package llc.slacker.openime

/** Converts the editable openIME pre-edit string into Rime's input syntax. */
internal object RimeInputNormalizer {
    fun normalize(input: String): String = input
        .lowercase()
        .trim()
        .replace(Regex("[|\\s]+"), "'")
        .trim('\'')
}
