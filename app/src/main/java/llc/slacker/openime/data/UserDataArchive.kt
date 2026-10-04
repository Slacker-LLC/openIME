package llc.slacker.openime.data

import android.content.Context
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeTheme
import org.json.JSONArray
import org.json.JSONObject

internal data class ArchiveQuickPhrase(
    val category: String,
    val text: String,
    val inputCode: String,
)

internal data class ArchiveCustomSymbol(
    val group: String,
    val symbol: String,
    val pinned: Boolean,
)

internal data class ArchiveUserPhrase(
    val code: String,
    val text: String,
    val frequency: Int,
    val lastUsed: Long,
)

internal data class ArchiveSettings(
    val theme: String,
    val appearance: String,
    val sound: Boolean,
    val haptic: Boolean,
    val popup: Boolean,
    val fuzzy: Boolean,
    val swipeUpDigits: Boolean,
    val preferredChineseMode: String,
    val keyboardHeightPercent: Int,
    val floatingWidthPercent: Int,
    val floatingOpacityPercent: Int,
    val letterHints: Boolean = true,
    val emojiAssociation: Boolean = true,
    val voiceStripFillers: Boolean = true,
    val voicePunctuationAsSpace: Boolean = false,
    val hapticStrengthPercent: Int = 100,
)

internal data class RimeUserDictionaryArchive(
    val name: String,
    val content: String,
)

internal data class UserDataArchive(
    val schemaVersion: Int = UserDataArchiveCodec.SCHEMA_VERSION,
    val quickPhrases: List<ArchiveQuickPhrase>,
    val customSymbols: List<ArchiveCustomSymbol>,
    val userPhrases: List<ArchiveUserPhrase>,
    val settings: ArchiveSettings,
    val rimeUserDictionaries: List<RimeUserDictionaryArchive> = emptyList(),
)

internal data class UserDataImportPreview(
    val quickPhrasesToAdd: Int,
    val customSymbolsToAdd: Int,
    val userPhrasesToAdd: Int,
    val settingsWillChange: Boolean,
    val rimeDictionaryFiles: Int,
) {
    val totalKotlinItemsToAdd: Int
        get() = quickPhrasesToAdd + customSymbolsToAdd + userPhrasesToAdd
}

internal object UserDataArchiveCodec {
    const val SCHEMA_VERSION = 1

    fun encode(value: UserDataArchive): String {
        val root = JSONObject()
            .put("schema_version", value.schemaVersion)
            .put("quick_phrases", JSONArray().apply {
                value.quickPhrases.forEach { item ->
                    put(
                        JSONObject()
                            .put("category", item.category)
                            .put("text", item.text)
                            .put("input_code", item.inputCode),
                    )
                }
            })
            .put("custom_symbols", JSONArray().apply {
                value.customSymbols.forEach { item ->
                    put(
                        JSONObject()
                            .put("group", item.group)
                            .put("symbol", item.symbol)
                            .put("pinned", item.pinned),
                    )
                }
            })
            .put("user_phrases", JSONArray().apply {
                value.userPhrases.forEach { item ->
                    put(
                        JSONObject()
                            .put("code", item.code)
                            .put("text", item.text)
                            .put("frequency", item.frequency)
                            .put("last_used", item.lastUsed),
                    )
                }
            })
            .put("settings", settingsToJson(value.settings))
            .put("rime_user_dictionaries", JSONArray().apply {
                value.rimeUserDictionaries.forEach { item ->
                    put(
                        JSONObject()
                            .put("name", item.name)
                            .put("content", item.content),
                    )
                }
            })
        return root.toString(2)
    }

    fun decode(raw: String): UserDataArchive {
        val root = JSONObject(raw)
        val version = root.optInt("schema_version", -1)
        require(version == SCHEMA_VERSION) {
            "Unsupported openIME data schema: $version"
        }
        return UserDataArchive(
            schemaVersion = version,
            quickPhrases = root.optJSONArray("quick_phrases").toObjects { item ->
                ArchiveQuickPhrase(
                    category = item.optString("category", "常用").ifBlank { "常用" },
                    text = item.optString("text", "").trim(),
                    inputCode = item.optString("input_code", "").trim(),
                )
            }.filter { it.text.isNotEmpty() },
            customSymbols = root.optJSONArray("custom_symbols").toObjects { item ->
                ArchiveCustomSymbol(
                    group = item.optString("group", "自定义").ifBlank { "自定义" },
                    symbol = item.optString("symbol", "").trim(),
                    pinned = item.optBoolean("pinned", false),
                )
            }.filter { it.symbol.isNotEmpty() },
            userPhrases = root.optJSONArray("user_phrases").toObjects { item ->
                ArchiveUserPhrase(
                    code = item.optString("code", "").trim(),
                    text = item.optString("text", "").trim(),
                    frequency = item.optInt("frequency", 1).coerceAtLeast(1),
                    lastUsed = item.optLong("last_used", 0L).coerceAtLeast(0L),
                )
            }.filter { it.code.isNotEmpty() && it.text.isNotEmpty() },
            settings = settingsFromJson(root.optJSONObject("settings") ?: JSONObject()),
            rimeUserDictionaries =
                root.optJSONArray("rime_user_dictionaries").toObjects { item ->
                    RimeUserDictionaryArchive(
                        name = item.optString("name", "").trim(),
                        content = item.optString("content", ""),
                    )
                }.filter {
                    isSafeRimeDictionaryName(it.name) && it.content.isNotEmpty()
                }.distinctBy { it.name },
        )
    }

    internal fun isSafeRimeDictionaryName(name: String): Boolean =
        name.length in 1..128 &&
            name != "." &&
            name != ".." &&
            name.none { char -> char == '/' || char == '\\' || char == '\u0000' }

    private fun settingsToJson(value: ArchiveSettings): JSONObject =
        JSONObject()
            .put("theme", value.theme)
            .put("appearance", value.appearance)
            .put("sound", value.sound)
            .put("haptic", value.haptic)
            .put("haptic_strength_percent", value.hapticStrengthPercent)
            .put("popup", value.popup)
            .put("fuzzy", value.fuzzy)
            .put("swipe_up_digits", value.swipeUpDigits)
            .put("preferred_chinese_mode", value.preferredChineseMode)
            .put("keyboard_height_percent", value.keyboardHeightPercent)
            .put("floating_width_percent", value.floatingWidthPercent)
            .put("floating_opacity_percent", value.floatingOpacityPercent)
            .put("letter_hints", value.letterHints)
            .put("emoji_association", value.emojiAssociation)
            .put("voice_strip_fillers", value.voiceStripFillers)
            .put("voice_punctuation_as_space", value.voicePunctuationAsSpace)

    private fun settingsFromJson(value: JSONObject): ArchiveSettings =
        ArchiveSettings(
            theme = value.optString("theme", ImeTheme.IOS.name),
            appearance = value.optString("appearance", ImeAppearance.SYSTEM.name),
            sound = value.optBoolean("sound", true),
            haptic = value.optBoolean("haptic", true),
            hapticStrengthPercent = value.optInt("haptic_strength_percent", 100).coerceIn(10, 100),
            popup = value.optBoolean("popup", false),
            fuzzy = value.optBoolean("fuzzy", false),
            swipeUpDigits = value.optBoolean("swipe_up_digits", true),
            preferredChineseMode = value.optString(
                "preferred_chinese_mode",
                KeyboardMode.PINYIN_26.name,
            ),
            keyboardHeightPercent =
                value.optInt("keyboard_height_percent", 100).coerceIn(80, 120),
            floatingWidthPercent =
                value.optInt("floating_width_percent", 88).coerceIn(72, 96),
            floatingOpacityPercent =
                value.optInt("floating_opacity_percent", 100).coerceIn(82, 100),
            letterHints = value.optBoolean("letter_hints", true),
            emojiAssociation = value.optBoolean("emoji_association", true),
            voiceStripFillers = value.optBoolean("voice_strip_fillers", true),
            voicePunctuationAsSpace =
                value.optBoolean("voice_punctuation_as_space", false),
        )

    private inline fun <T> JSONArray?.toObjects(block: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let { add(block(it)) }
            }
        }
    }
}

internal object UserDataArchiveMerger {
    fun preview(existing: UserDataArchive, incoming: UserDataArchive): UserDataImportPreview {
        val existingQuick = existing.quickPhrases.mapTo(HashSet(), ::quickKey)
        val existingSymbols = existing.customSymbols.mapTo(HashSet(), ::symbolKey)
        val existingUser = existing.userPhrases.mapTo(HashSet(), ::userPhraseKey)
        return UserDataImportPreview(
            quickPhrasesToAdd = incoming.quickPhrases.distinctBy(::quickKey)
                .count { quickKey(it) !in existingQuick },
            customSymbolsToAdd = incoming.customSymbols.distinctBy(::symbolKey)
                .count { symbolKey(it) !in existingSymbols },
            userPhrasesToAdd = incoming.userPhrases.distinctBy(::userPhraseKey)
                .count { userPhraseKey(it) !in existingUser },
            settingsWillChange = incoming.settings != existing.settings,
            rimeDictionaryFiles = incoming.rimeUserDictionaries
                .map { it.name }
                .distinct()
                .size,
        )
    }

    fun merge(existing: UserDataArchive, incoming: UserDataArchive): UserDataArchive {
        val quick = existing.quickPhrases.toMutableList()
        val quickKeys = quick.mapTo(HashSet(), ::quickKey)
        incoming.quickPhrases.forEach { item ->
            if (quickKeys.add(quickKey(item))) quick += item
        }

        val symbols = existing.customSymbols.toMutableList()
        val symbolKeys = symbols.mapTo(HashSet(), ::symbolKey)
        incoming.customSymbols.forEach { item ->
            if (symbolKeys.add(symbolKey(item))) symbols += item
        }

        val users = LinkedHashMap<String, ArchiveUserPhrase>()
        existing.userPhrases.forEach { users[userPhraseKey(it)] = it }
        incoming.userPhrases.forEach { item ->
            val key = userPhraseKey(item)
            val current = users[key]
            users[key] = if (current == null) {
                item
            } else {
                current.copy(
                    frequency = maxOf(current.frequency, item.frequency),
                    lastUsed = maxOf(current.lastUsed, item.lastUsed),
                )
            }
        }

        val rime = LinkedHashMap<String, RimeUserDictionaryArchive>()
        existing.rimeUserDictionaries.forEach { rime[it.name] = it }
        incoming.rimeUserDictionaries.forEach { rime[it.name] = it }

        return UserDataArchive(
            quickPhrases = quick,
            customSymbols = symbols,
            userPhrases = users.values.toList(),
            settings = incoming.settings,
            rimeUserDictionaries = rime.values.toList(),
        )
    }

    private fun quickKey(value: ArchiveQuickPhrase): String =
        listOf(value.category.trim(), value.text.trim(), value.inputCode.trim().lowercase())
            .joinToString("\u0000")

    private fun symbolKey(value: ArchiveCustomSymbol): String =
        listOf(value.group.trim(), value.symbol.trim()).joinToString("\u0000")

    private fun userPhraseKey(value: ArchiveUserPhrase): String =
        listOf(value.code.trim().lowercase(), value.text.trim()).joinToString("\u0000")
}

internal object UserDataRepository {
    fun snapshot(
        context: Context,
        rimeUserDictionaries: List<RimeUserDictionaryArchive> = emptyList(),
    ): UserDataArchive {
        UserPhraseRepository.configure(context)
        return UserDataArchive(
            quickPhrases = QuickPhraseRepository.load(context).map {
                ArchiveQuickPhrase(
                    category = it.category,
                    text = it.text,
                    inputCode = it.inputCode,
                )
            },
            customSymbols = CustomSymbolRepository.load(context).map {
                ArchiveCustomSymbol(
                    group = it.group,
                    symbol = it.symbol,
                    pinned = it.pinned,
                )
            },
            userPhrases = UserPhraseRepository.exportArchiveEntries(),
            settings = ArchiveSettings(
                theme = ImeSettingsRepository.loadTheme(context).name,
                appearance = ImeSettingsRepository.loadAppearance(context).name,
                sound = ImeSettingsRepository.loadSound(context),
                haptic = ImeSettingsRepository.loadHaptic(context),
                hapticStrengthPercent = ImeSettingsRepository.loadHapticStrengthPercent(context),
                popup = ImeSettingsRepository.loadPopup(context),
                fuzzy = ImeSettingsRepository.loadFuzzy(context),
                swipeUpDigits = ImeSettingsRepository.loadSwipeUpDigits(context),
                preferredChineseMode =
                    ImeSettingsRepository.loadPreferredChineseMode(context).name,
                keyboardHeightPercent =
                    ImeSettingsRepository.loadKeyboardHeightPercent(context),
                floatingWidthPercent =
                    ImeSettingsRepository.loadFloatingWidthPercent(context),
                floatingOpacityPercent =
                    ImeSettingsRepository.loadFloatingOpacityPercent(context),
                letterHints = ImeSettingsRepository.loadLetterHints(context),
                emojiAssociation = ImeSettingsRepository.loadEmojiAssociation(context),
                voiceStripFillers = ImeSettingsRepository.loadVoiceStripFillers(context),
                voicePunctuationAsSpace =
                    ImeSettingsRepository.loadVoicePunctuationAsSpace(context),
            ),
            rimeUserDictionaries = rimeUserDictionaries,
        )
    }

    fun preview(context: Context, incoming: UserDataArchive): UserDataImportPreview =
        UserDataArchiveMerger.preview(snapshot(context), incoming)

    fun importKotlinData(context: Context, incoming: UserDataArchive): UserDataImportPreview {
        val preview = preview(context, incoming)

        val quickKeys = QuickPhraseRepository.load(context)
            .mapTo(HashSet()) {
                Triple(it.category.trim(), it.text.trim(), it.inputCode.trim().lowercase())
            }
        incoming.quickPhrases.forEach { item ->
            val key = Triple(
                item.category.trim(),
                item.text.trim(),
                item.inputCode.trim().lowercase(),
            )
            if (quickKeys.add(key)) {
                QuickPhraseRepository.upsert(
                    context = context,
                    id = 0L,
                    category = item.category,
                    text = item.text,
                    inputCode = item.inputCode,
                )
            }
        }

        val symbolKeys = CustomSymbolRepository.load(context)
            .mapTo(HashSet()) { it.group.trim() to it.symbol.trim() }
        incoming.customSymbols.forEach { item ->
            val key = item.group.trim() to item.symbol.trim()
            if (symbolKeys.add(key)) {
                val added = CustomSymbolRepository.upsert(
                    context = context,
                    id = 0L,
                    group = item.group,
                    symbol = item.symbol,
                )
                if (item.pinned && added != null) {
                    CustomSymbolRepository.togglePinned(context, added.id)
                }
            }
        }

        UserPhraseRepository.mergeArchiveEntries(incoming.userPhrases)
        applySettings(context, incoming.settings)
        return preview
    }

    private fun applySettings(context: Context, value: ArchiveSettings) {
        ImeSettingsRepository.saveTheme(
            context,
            runCatching { ImeTheme.valueOf(value.theme) }.getOrDefault(ImeTheme.IOS),
        )
        ImeSettingsRepository.saveAppearance(
            context,
            runCatching { ImeAppearance.valueOf(value.appearance) }
                .getOrDefault(ImeAppearance.SYSTEM),
        )
        ImeSettingsRepository.saveSound(context, value.sound)
        ImeSettingsRepository.saveHaptic(context, value.haptic)
        ImeSettingsRepository.saveHapticStrengthPercent(context, value.hapticStrengthPercent)
        ImeSettingsRepository.savePopup(context, value.popup)
        ImeSettingsRepository.saveFuzzy(context, value.fuzzy)
        ImeSettingsRepository.saveSwipeUpDigits(context, value.swipeUpDigits)
        ImeSettingsRepository.savePreferredChineseMode(
            context,
            runCatching { KeyboardMode.valueOf(value.preferredChineseMode) }
                .getOrDefault(KeyboardMode.PINYIN_26),
        )
        ImeSettingsRepository.saveKeyboardHeightPercent(
            context,
            value.keyboardHeightPercent,
        )
        ImeSettingsRepository.saveFloatingWidthPercent(
            context,
            value.floatingWidthPercent,
        )
        ImeSettingsRepository.saveFloatingOpacityPercent(
            context,
            value.floatingOpacityPercent,
        )
        ImeSettingsRepository.saveLetterHints(context, value.letterHints)
        ImeSettingsRepository.saveEmojiAssociation(context, value.emojiAssociation)
        ImeSettingsRepository.saveVoiceStripFillers(context, value.voiceStripFillers)
        ImeSettingsRepository.saveVoicePunctuationAsSpace(
            context,
            value.voicePunctuationAsSpace,
        )
    }
}
