package llc.slacker.openime.core

/**
 * 输入中的拼音显示在哪里：候选栏（默认），还是应用自己的输入框。
 *
 * 参考皮肤把这一项写作「输入拼音显示」，两个取值「显示在候选栏」与
 * 「显示在输入框」（`kbd_layout_pinyin_location_title`、
 * `kbd_layout_pinyin_location_in_keyboard`、
 * `kbd_layout_pinyin_location_in_editor`），默认是候选栏
 * （`xml/settings_preferences` 里 `pinyin_location_style` 的 `defaultValue=0`，
 * APK 内路径 `res/cU.xml`）。选了输入框时，参考把拼音作为 composing region 加
 * composing text 写进输入框（`KeyboardJni.cacheCompStrAndPreedit` →
 * `UpdatePreedit` → `setComposingRegion`/`setComposingText`），并且只在没有命中
 * 它的拦截名单应用时这么做（`KeyboardJni.isSupportPinyinInEditor`）。我们这边
 * 对应的是 [INPUT_BOX]：拼音交给应用的输入框显示，键盘上那行预编辑不再重复显示。
 */
enum class PinyinLocation(val key: String, val label: String) {
    /** 拼音留在键盘自己的预编辑行里，输入框保持原样。 */
    CANDIDATE_BAR("candidate_bar", "显示在候选栏"),

    /** 拼音写进应用的输入框（composing text），键盘上不再重复显示。 */
    INPUT_BOX("input_box", "显示在输入框"),
    ;

    companion object {
        fun fromKey(key: String?): PinyinLocation =
            entries.firstOrNull { it.key == key } ?: CANDIDATE_BAR
    }
}
