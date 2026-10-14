package llc.slacker.openime.candidate

/**
 * 等 Rime 回答的那一帧只显示含汉字的候选。本地引擎在词库没命中时会把输入原样
 * 当候选返回，显示出来就是"英文闪一帧再变中文"。
 *
 * 汉字判定用 Unicode 脚本属性（Han），覆盖 CJK 统一表意文字的基本区与扩展区 A–G、
 * 兼容表意文字，以及部首补充等。不能用 `code > 0x7f`：那样会把全角标点、
 * 拉丁扩展字母（如 é）也当成"汉字"，导致原串混过去。
 */
internal object CandidatePreviewPolicy {
    fun chineseOnly(candidates: List<String>): List<String> =
        candidates.filter { containsHan(it) }

    fun containsHan(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isHan(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    internal fun isHan(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
}
