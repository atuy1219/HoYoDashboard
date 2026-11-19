package com.atuy.hoyodashboard

import org.json.JSONArray
import org.json.JSONObject

private val referenceId = Regex("[A-Za-z][0-9]{1,20}")
private val referenceMarkup = Regex("""\{LINK#([A-Za-z][0-9]{1,20})\}(.*?)\{/LINK[})]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
internal const val GAME_REFERENCE_SCHEME = "hoyodashboard-reference:"

internal data class GameTextReference(val id: String, val label: String)

internal fun String.withGameReferenceAnchors(): String {
    if (!referenceMarkup.containsMatchIn(this)) return this
    return referenceMarkup.replace(this) { match ->
        "<a href=\"$GAME_REFERENCE_SCHEME${match.groupValues[1].uppercase()}\">${match.groupValues[2]}</a>"
    }.replace("\\n", "<br>").replace("\n", "<br>")
}

internal fun String.withoutGameReferenceMarkup(): String =
    referenceMarkup.replace(this) { it.groupValues[2] }

internal fun gameReferenceId(uri: String): String? =
    uri.takeIf { it.startsWith(GAME_REFERENCE_SCHEME) }
        ?.removePrefix(GAME_REFERENCE_SCHEME)?.takeIf(referenceId::matches)?.uppercase()

internal fun gameReferenceUrl(id: String): String? {
    val normalized = id.uppercase().takeIf(referenceId::matches) ?: return null
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
                            referenceId.matches(id) -> id.uppercase()
                            key == "skill_id" && id.matches(Regex("[0-9]{1,20}")) -> "S$id"
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
