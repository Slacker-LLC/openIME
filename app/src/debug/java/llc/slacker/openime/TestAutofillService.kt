package llc.slacker.openime

import android.annotation.TargetApi
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.app.slice.Slice
import android.app.slice.SliceSpec
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec

/**
 * Debug-only autofill provider for the end-to-end check of the keyboard's
 * inline suggestion strip. It offers two canned accounts for any field whose
 * autofill hint is `username`, and a canned password for `password`. It builds
 * the chip slice by hand in the androidx.autofill v1 layout so the app needs no
 * extra dependency.
 */
@TargetApi(30)
class TestAutofillService : AutofillService() {
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val fields = mutableListOf<Pair<AutofillId, String>>()
        request.fillContexts.lastOrNull()?.let { collect(it, fields) }
        val specs = request.inlineSuggestionsRequest?.inlinePresentationSpecs.orEmpty()
        val response = FillResponse.Builder()
        var datasets = 0
        fields.forEach { (id, hint) ->
            val values = if (hint == "password") PASSWORDS else USERNAMES
            values.forEachIndexed { index, (label, value) ->
                val spec = specs.getOrNull(index) ?: specs.lastOrNull()
                val menu = RemoteViews("android", android.R.layout.simple_list_item_1).apply {
                    setTextViewText(android.R.id.text1, label)
                }
                val dataset = Dataset.Builder(menu).setValue(id, AutofillValue.forText(value), menu)
                if (spec != null) dataset.setInlinePresentation(chip(label, spec))
                response.addDataset(dataset.build())
                datasets++
            }
        }
        if (datasets == 0) callback.onSuccess(null) else callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()

    private fun collect(context: FillContext, out: MutableList<Pair<AutofillId, String>>) {
        val structure = context.structure
        for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode, out)
    }

    private fun walk(node: AssistStructure.ViewNode, out: MutableList<Pair<AutofillId, String>>) {
        val hint = node.autofillHints?.firstOrNull { it == "username" || it == "password" }
        val id = node.autofillId
        if (hint != null && id != null) out += id to hint
        for (i in 0 until node.childCount) walk(node.getChildAt(i), out)
    }

    private fun chip(title: String, spec: InlinePresentationSpec): InlinePresentation {
        val attribution = PendingIntent.getService(
            this,
            0,
            Intent(this, TestAutofillService::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val sliceBuilder = Slice.Builder(Uri.parse("inline.slice"), SliceSpec(V1, 1))
        sliceBuilder.addText(title, null, listOf("inline_title"))
        sliceBuilder.addAction(
            attribution,
            Slice.Builder(sliceBuilder).addHints(listOf("inline_attribution")).build(),
            null,
        )
        sliceBuilder.addText(title, null, listOf("inline_content_description"))
        return InlinePresentation(sliceBuilder.build(), spec, false)
    }

    private companion object {
        const val V1 = "androidx.autofill.inline.ui.version:v1"
        val USERNAMES = listOf("工作账号" to "work@openime.dev", "个人账号" to "me@openime.dev")
        val PASSWORDS = listOf("测试密码" to "openime-test-secret")
    }
}
