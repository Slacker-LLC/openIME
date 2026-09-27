package llc.slacker.openime

import android.inputmethodservice.InputMethodService
import android.view.View

/**
 * Numeric-keyboard post-build decoration only.
 *
 * Keeps the regular numeric renderer shared across number/decimal/phone
 * editors while applying the editor-specific side rail and phone literals.
 */
internal object NumericKeypadDecorator {
    private const val DIGITS_RAIL_TAG = "digits-symbol-scroll"
    private const val DIGITS_CONTENT_TAG = "digits-symbol-scroll-content"

    fun decorate(
        root: View,
        onCommit: (String) -> Unit,
        onFeedback: () -> Unit,
    ) {
        decoratePhoneKeypad(root, onCommit, onFeedback)

        SymbolRailRenderer.decorate(
            root = root,
            sourceTag = "digits-symbol-stack",
            railTag = DIGITS_RAIL_TAG,
            contentTag = DIGITS_CONTENT_TAG,
            contentDescription = "数字键盘符号，上下滑动查看更多",
            symbols = digitSymbols(root),
            tagPrefix = "digit-symbol:",
            onCommit = onCommit,
            onFeedback = onFeedback,
        )
    }

    private fun decoratePhoneKeypad(
        root: View,
        onCommit: (String) -> Unit,
        onFeedback: () -> Unit,
    ) {
        val service = root.context as? InputMethodService ?: return
        val kind = EditorInfoAdapter.kind(service.currentInputEditorInfo)
        if (kind != EditorInfoAdapter.EditorKind.PHONE) return
        if (root.findViewWithTag<View>("digits-layout") == null) return

        val symbolContent = root.findViewWithTag<View>("digits-symbol-stack")
            ?: root.findViewWithTag<View>(DIGITS_CONTENT_TAG)
        (symbolContent?.parent as? View)?.visibility = View.GONE

        PhoneKeypadPolicy.literalByTag.forEach { (tag, literal) ->
            val key = root.findViewWithTag<ImeKeyView>(tag) ?: return@forEach
            key.setMainText(literal)
            key.contentDescription = literal
            key.isLongClickable = false
            key.setOnLongClickListener(null)
            if (tag == "key-space") {
                key.setIcon(0)
                key.setOnTouchListener(null)
            }
            key.setOnClickListener {
                onFeedback()
                onCommit(literal)
            }
        }
    }

    private fun digitSymbols(root: View): List<String> =
        customSymbols(root) +
            listOf("%", "+", "−", "＊") +
            ImeData.symbols["常用"].orEmpty()
                .asSequence()
                .filter { it.isNotBlank() }
                .toList()
                .distinct()

    private fun customSymbols(root: View): List<String> =
        CustomSymbolRepository.load(root.context)
            .map { it.symbol }
            .filter { it.isNotBlank() }
}
