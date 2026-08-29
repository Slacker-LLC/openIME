package llc.slacker.openime

/** Minimal JNI surface for the embedded librime session. */
internal object RimeNative {
    init {
        System.loadLibrary("local_rime")
    }

    @JvmStatic
    external fun nativeStartup(sharedDir: String, userDir: String)

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
