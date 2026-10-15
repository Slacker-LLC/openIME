package llc.slacker.openime.editor

import llc.slacker.openime.core.PinyinLocation

/**
 * 输入中的拼音写不写进应用自己的输入框（composing text），还是留在键盘的预编辑
 * 行里。两件事都要成立：用户选了「显示在输入框」（见 [PinyinLocation]），而且这
 * 个输入框收得下。
 */
object ComposingTextPolicy {

    /**
     * 密码框收下预编辑就等于把拼音打进密码里，数字、日期这类字段只收自己的字符
     * 集，终端和游戏（TYPE_NULL）只吃按键事件，都不写。框的类型读不出来时按普通
     * 文本框处理，写进去——和参考皮肤默认「显示在输入框」的取向一致。
     */
    fun composesInEditor(
        location: PinyinLocation,
        passwordField: Boolean,
        kind: EditorInfoAdapter.EditorKind,
    ): Boolean {
        if (location != PinyinLocation.INPUT_BOX) return false
        if (passwordField) return false
        return when (kind) {
            EditorInfoAdapter.EditorKind.TEXT,
            EditorInfoAdapter.EditorKind.MULTILINE,
            EditorInfoAdapter.EditorKind.EMAIL,
            EditorInfoAdapter.EditorKind.URL,
            EditorInfoAdapter.EditorKind.UNKNOWN,
            -> true

            EditorInfoAdapter.EditorKind.PASSWORD,
            EditorInfoAdapter.EditorKind.NUMBER,
            EditorInfoAdapter.EditorKind.DECIMAL,
            EditorInfoAdapter.EditorKind.PHONE,
            EditorInfoAdapter.EditorKind.DATETIME,
            EditorInfoAdapter.EditorKind.RAW_KEYS,
            -> false
        }
    }
}
