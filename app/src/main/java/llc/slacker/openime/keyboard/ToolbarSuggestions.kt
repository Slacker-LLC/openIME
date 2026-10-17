package llc.slacker.openime.keyboard

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import llc.slacker.openime.data.ClipboardSensitivityPolicy
import llc.slacker.openime.data.ClipboardSuppression
import llc.slacker.openime.data.ImeSettingsRepository

/** What the toolbar offers to type: a fresh text-message code, the text just copied. */
internal data class ToolbarSuggestion(val kind: Kind, val label: String, val text: String, val id: String) {
    enum class Kind { SMS_CODE, CLIPBOARD }
}

internal object ToolbarSuggestions {
    private const val SMS_WINDOW_MS = 5 * 60_000L
    private const val CLIP_WINDOW_MS = 3 * 60_000L
    private const val CLIP_PREVIEW_CHARS = 200

    /** Both reads are best-effort and silent: a refused permission or empty clipboard gives no chip. */
    fun collect(context: Context, allowed: Boolean, skipClip: String?, skipSms: String? = null): List<ToolbarSuggestion> {
        if (!allowed) return emptyList()
        val out = mutableListOf<ToolbarSuggestion>()
        if (ImeSettingsRepository.loadSmsCodeChip(context)) smsCode(context)?.takeIf { it.id != skipSms }?.let(out::add)
        if (ImeSettingsRepository.loadClipboardChip(context)) freshClip(context)?.takeIf { it.id != skipClip }?.let(out::add)
        return out
    }

    private fun smsCode(context: Context): ToolbarSuggestion? {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val since = System.currentTimeMillis() - SMS_WINDOW_MS
            context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "date", "body"),
                "date > ?",
                arrayOf(since.toString()),
                "date DESC",
            )?.use { cursor ->
                var found: ToolbarSuggestion? = null
                var seen = 0
                while (found == null && seen < 5 && cursor.moveToNext()) {
                    found = SmsCodeExtractor.extract(cursor.getString(2).orEmpty())?.let { code ->
                        ToolbarSuggestion(ToolbarSuggestion.Kind.SMS_CODE, "验证码 $code", code,
                            "sms:${cursor.getLong(0)}:${cursor.getLong(1)}")
                    }
                    seen++
                }
                found
            }
        }.getOrNull()
    }

    private fun freshClip(context: Context): ToolbarSuggestion? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return null
        val sensitive = clip.description.extras?.let { extras ->
            ClipboardSensitivityPolicy.isSensitive { key -> extras.getBoolean(key, false) }
        } ?: false
        if (sensitive) return null
        val stamp = clip.description.timestamp
        if (stamp <= 0L || System.currentTimeMillis() - stamp > CLIP_WINDOW_MS) return null
        val text = runCatching {
            clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        }.getOrDefault("")
        return text.takeIf { it.isNotBlank() }?.let {
            val preview = it.take(CLIP_PREVIEW_CHARS).trimEnd { char -> char.isHighSurrogate() }
                .replace(Regex("\\s+"), " ").trim() + if (it.length > CLIP_PREVIEW_CHARS) "…" else ""
            ToolbarSuggestion(ToolbarSuggestion.Kind.CLIPBOARD, preview, it, ClipboardSuppression.fingerprint(stamp, it))
        }
    }
}
