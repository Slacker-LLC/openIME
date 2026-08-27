package llc.slacker.openime

/** Minimal JNI surface for the embedded librime session. */
internal object RimeNative {
    init {
        System.loadLibrary("local_rime")
    }

    @JvmStatic
    external fun nativeStartup(sharedDir: String, userDir: String)

    @JvmStatic
    external fun nativeSetInput(input: String): Array<String>

    @JvmStatic
    external fun nativeSelectCandidate(index: Int): String

    @JvmStatic
    external fun nativeCommitFirst(): String

    @JvmStatic
    external fun nativeClear()

    @JvmStatic
    external fun nativeShutdown()

    /** JNI boundary regression hook; does not touch the Rime session. */
    @JvmStatic
    external fun nativeUtf8RoundTripForTest(input: String): String
}

