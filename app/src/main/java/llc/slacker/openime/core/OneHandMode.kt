package llc.slacker.openime.core

/** 单手模式：把键盘收窄到一侧，方便单手握持时用拇指打字。 */
enum class OneHandMode(val key: String, val label: String) {
    OFF("off", "关闭"),
    LEFT("left", "左手"),
    RIGHT("right", "右手"),
    ;

    companion object {
        fun fromKey(key: String?): OneHandMode =
            entries.firstOrNull { it.key == key } ?: OFF
    }
}
