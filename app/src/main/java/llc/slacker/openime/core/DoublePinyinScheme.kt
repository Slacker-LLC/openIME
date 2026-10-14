package llc.slacker.openime.core

/** 双拼键位方案；参考键盘提供小鹤与自然码两种。 */
enum class DoublePinyinScheme(val key: String, val label: String, val schemaId: String) {
    FLYPY("flypy", "小鹤双拼", "double_pinyin_flypy"),
    NATURAL("natural", "自然码", "double_pinyin"),
    ;

    companion object {
        fun fromKey(key: String?): DoublePinyinScheme =
            entries.firstOrNull { it.key == key } ?: FLYPY
    }
}
