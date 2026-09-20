package llc.slacker.openime

/** Small high-confidence 9-key overrides. Every entry is code-validated in tests. */
internal object NineKeyPresets {
    val combinations: Map<String, List<String>> = linkedMapOf(
        "64" to listOf("ni", "mi", "oh"),
        "64426" to listOf("nihao"),
        "9664" to listOf("yong"),
        "426" to listOf("hao"),
        "4664" to listOf("gong"),
        "944" to listOf("zhi"),
        "943" to listOf("zhe"),
        "33" to listOf("de"),
        "96" to listOf("wo", "yo"),
        "82" to listOf("ta"),
        "744" to listOf("shi"),
        "9426" to listOf("xian"),
        "94264" to listOf("xiang"),
        "24364" to listOf("cheng"),
        "54264" to listOf("jiang"),
        "7487832" to listOf("shurufa"),
        "934946" to listOf("weixin"),
    )
}
