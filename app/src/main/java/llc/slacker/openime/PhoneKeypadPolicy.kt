package llc.slacker.openime

/** Production remapping for TYPE_CLASS_PHONE without duplicating the numeric renderer. */
internal object PhoneKeypadPolicy {
    val literalByTag: LinkedHashMap<String, String> = linkedMapOf(
        "key-space" to "*",
        "key:." to "+",
        "key:@" to "#",
    )
}
