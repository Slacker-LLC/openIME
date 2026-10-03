package llc.slacker.openime.hotword

/**
 * One word list: either shipped in the APK or imported by the user.
 *
 * A pack is plain data. Where it came from and whether it is switched on are
 * the store's business; what it does to recognized text is the corrector's.
 */
internal data class HotwordPack(
    val id: String,
    val title: String,
    val description: String,
    val origin: Origin,
    /** Initial switch state for a pack the user has not touched yet. */
    val defaultEnabled: Boolean,
    val words: List<String>,
    /** Lines that were dropped because they cannot be matched by sound. */
    val rejectedLines: Int,
) {
    enum class Origin { BUNDLED, IMPORTED }
}
