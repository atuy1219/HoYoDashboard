package com.atuy.hoyodashboard

import org.json.JSONArray
import org.json.JSONObject

private const val REFERENCE_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}"
private val referenceId = Regex(REFERENCE_ID_PATTERN)
private val referenceMarkup = Regex("""\{LINK#($REFERENCE_ID_PATTERN)\}(.*?)\{/LINK[})]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
internal const val GAME_REFERENCE_SCHEME = "hoyodashboard-reference:"

internal data class GameTextReference(val id: String, val label: String)
internal data class TextLink(val start: Int, val end: Int, val url: String, val label: String)
internal data class LinkedText(val text: String, val links: List<TextLink>)

internal fun String.hasGameReferences(): Boolean = referenceMarkup.containsMatchIn(this)

internal fun String.withoutGameReferenceMarkup(): String =
    referenceMarkup.replace(this) { it.groupValues[2] }

internal fun parseGameReferences(text: String, webLinks: List<TextLink> = emptyList()): LinkedText {
    val matches = referenceMarkup.findAll(text).toList()
    if (matches.isEmpty()) return LinkedText(text, webLinks.sortedBy { it.start })
    val visible = StringBuilder()
    val references = mutableListOf<TextLink>()
    val removed = mutableListOf<IntRange>()
    var cursor = 0
    for (match in matches) {
        val label = checkNotNull(match.groups[2])
        visible.append(text.substring(cursor, match.range.first))
        val start = visible.length
        visible.append(label.value)
        if (label.value.isNotEmpty()) {
            references += TextLink(start, visible.length,
                GAME_REFERENCE_SCHEME + match.groupValues[1].uppercase(), label.value.trim())
        }
        removed += match.range.first until label.range.first
        removed += (label.range.last + 1)..match.range.last
        cursor = match.range.last + 1
    }
    visible.append(text.substring(cursor))

    fun adjustedOffset(offset: Int): Int = offset - removed.sumOf { range ->
        (offset - range.first).coerceIn(0, range.last - range.first + 1)
    }

    for (link in webLinks) {
        val start = adjustedOffset(link.start)
        val end = adjustedOffset(link.end)
        if (end > start && references.none { it.start < end && it.end > start }) {
            references += link.copy(start = start, end = end)
        }
    }
    return LinkedText(visible.toString(), references.sortedBy { it.start })
}

internal suspend fun LinkedText.mapText(transform: suspend (String) -> String): LinkedText {
    if (links.isEmpty()) return LinkedText(transform(text), emptyList())
    val output = StringBuilder()
    val mapped = mutableListOf<TextLink>()
    var cursor = 0
    for (link in links) {
        if (cursor < link.start) output.append(transform(text.substring(cursor, link.start)))
        val start = output.length
        val label = text.substring(link.start, link.end)
        output.append(transform(label).ifBlank { label })
        mapped += link.copy(start = start, end = output.length)
        cursor = link.end
    }
    if (cursor < text.length) output.append(transform(text.substring(cursor)))
    return LinkedText(output.toString(), mapped)
}

internal fun gameReferenceId(uri: String): String? =
    uri.takeIf { it.startsWith(GAME_REFERENCE_SCHEME) }
        ?.removePrefix(GAME_REFERENCE_SCHEME)?.takeIf(referenceId::matches)?.uppercase()

internal fun gameReferenceUrl(id: String): String? {
    val normalized = id.uppercase().takeIf { it.matches(Regex("[NS][0-9]{1,20}")) } ?: return null
    return when (normalized.first()) {
        'N' -> "https://ghost.mwb.moe/ja/genshin/game_link/$normalized"
        'S' -> "https://ghost.mwb.moe/ja/genshin/avatar_skill/${normalized.drop(1)}"
        else -> null
    }
}

internal data class GameReferenceEntry(val title: String, val description: String, val icon: String?)

internal class GameReferenceIndex(root: JSONObject) {
    private val byId = mutableMapOf<String, MutableList<GameReferenceEntry>>()
    private val byName = mutableMapOf<String, MutableList<GameReferenceEntry>>()

    init {
        index(root)
    }

    fun find(reference: GameTextReference): GameReferenceEntry? =
        byId[reference.id.uppercase()]?.distinct()?.singleOrNull()
            ?: byName[reference.label.cleanReferenceName()]?.distinct()?.singleOrNull()

    private fun index(value: Any?) {
        when (value) {
            is JSONObject -> {
                val title = value.firstText("name", "title", "skill_name", "name_mi18n")
                val description = value.firstText("desc", "description", "effect", "skill_desc", "skill_description", "content")
                if (title != null && description != null) {
                    val entry = GameReferenceEntry(title, description, value.firstText("icon", "icon_url", "skill_icon"))
                    byName.getOrPut(title.cleanReferenceName()) { mutableListOf() }.add(entry)
                    for (key in listOf("id", "link_id", "reference_id", "skill_id")) {
                        val id = value.optString(key)
                        val normalized = when {
                            key == "skill_id" && id.matches(Regex("[0-9]{1,20}")) -> "S$id"
                            key == "link_id" && id.matches(Regex("[0-9]{1,20}")) -> "N$id"
                            referenceId.matches(id) -> id.uppercase()
                            else -> null
                        }
                        if (normalized != null) byId.getOrPut(normalized) { mutableListOf() }.add(entry)
                    }
                }
                value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key == "data" && child is String) {
                        val json = child.trim()
                        if (json.startsWith("{")) index(runCatching { JSONObject(json) }.getOrNull())
                        else if (json.startsWith("[")) index(runCatching { JSONArray(json) }.getOrNull())
                    } else index(child)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) index(value.opt(i))
        }
    }
}

private fun JSONObject.firstText(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { (opt(it) as? String)?.takeIf(String::isNotBlank) }

private fun String.cleanReferenceName(): String =
    withoutGameReferenceMarkup().replace(Regex("<[^>]*>"), "").trim()
