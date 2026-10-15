package llc.slacker.openime.keyboard

/** Production remapping for TYPE_CLASS_PHONE without duplicating the numeric renderer. */
internal object PhoneKeypadPolicy {
    val literalByTag: LinkedHashMap<String, String> = linkedMapOf(
        "key-space" to "*",
        "key:." to "+",
        // The reference's number page has no `#` cell; its symbol-page cell
        // carries the dialer's own symbol in a phone field (our own rule).
        "key:symbol" to "#",
    )
}
