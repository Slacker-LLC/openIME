package llc.slacker.openime

/** Minimal JNI surface for the embedded librime session. */
internal object RimeNative {
    init {
        System.loadLibrary("local_rime")
    }

    @JvmStatic
    /** [fullCheck] recompiles every schema config; see RimeEngine.syncFuzzyRulesFile. */
    external fun nativeStartup(sharedDir: String, userDir: String, fullCheck: Boolean)

    @JvmStatic
    external fun nativeSelectSchema(schemaId: String): Boolean

    /**
     * Nullable on purpose: the native helper returns nullptr when the String
     * class lookup or the array allocation fails (OOM, exhausted JNI local ref
     * table), and it can return a partially filled array whose tail slots are
     * still null. Declaring these non-nullable made Kotlin insert an intrinsic
     * null check that turned an allocation failure into an NPE in the middle
     * of candidate rendering.
     */
    @JvmStatic
    external fun nativeSetInput(input: String): Array<String>?

    @JvmStatic
    external fun nativeSelectCandidate(index: Int): String?

    /**
     * Absolute input offset each of the first [count] candidates of the current
     * input spells (-1 when unknown). Shorter than the input = partial match.
     */
    @JvmStatic
    external fun nativeCandidateEnds(count: Int): IntArray?

    /** True only when the active visible candidate is backed by Rime user data. */
    @JvmStatic
    external fun nativeIsUserLearnedCandidate(index: Int): Boolean

    /** Remove a deletable candidate from the active Rime user dictionary. */
    @JvmStatic
    external fun nativeDeleteCandidate(index: Int): Boolean

    /** Export every available Rime user dictionary as UTF-8 text files. */
    @JvmStatic
    external fun nativeExportUserDictionaries(targetDir: String): Array<String>?

    /** Merge one UTF-8 user dictionary snapshot back into the named Rime dictionary. */
    @JvmStatic
    external fun nativeImportUserDictionary(dictName: String, sourceFile: String): Int


    @JvmStatic
    external fun nativeCommitFirst(): String?

    @JvmStatic
    external fun nativeClear()

    @JvmStatic
    external fun nativeShutdown()

    /** JNI boundary regression hook; does not touch the Rime session. */
    @JvmStatic
    external fun nativeUtf8RoundTripForTest(input: String): String
}
