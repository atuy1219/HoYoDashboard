package com.atuy.hoyodashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class ResultLink(val label: String, val method: String, val arguments: Map<String, String>)

private val titleFields = listOf(
    "name", "title", "name_mi18n", "name_text", "nickname", "nick_name", "avatar_name", "role_name",
    "task_name", "item_name", "monster_name", "maze_name", "stage_name", "level_name",
    "pool_name", "full_name_mi18n", "skill_name", "ending_record_name", "name_cn"
)
private val portraitFields = listOf(
    "icon", "icon_url", "avatar_icon", "avatar_url", "avatar_icon_url", "avatar_square_icon",
    "square_icon", "square_icon_url", "role_square_url", "role_icon", "monster_icon", "head_icon", "card_icon", "image_url",
    "big_icon", "role_image", "avatar_background_path", "cur_head_icon_url"
)
private val bannerFields = listOf("banner", "img", "imgs", "cover", "cover_url", "cover_urls", "image", "picture")
private val proseFields = setOf(
    "desc", "description", "content", "text", "summary", "intro", "brief", "simple_desc",
    "desc_mi18n", "effect_description", "skill_description", "two_piece_description", "four_piece_description",
    "introduction", "introduce", "rich_desc", "window_text"
)
private val hiddenFields = setOf(
    "retcode", "message", "next_page_token", "next_end_id", "next_max_id", "end_id", "cursor",
    "last_id", "client_param", "session_param", "has_more", "is_last", "is_end", "authkey", "token", "cookie",
    "cookie_token", "cookie_token_v2", "stoken", "stoken_v2", "ltoken", "ltoken_v2", "combo_token",
    "game_token", "login_ticket", "pass_token", "verify_str", "uuid", "lang", "original_lang", "tzinfo",
    "property_map", "relic_property_options", "version_id", "ann_id", "schedule_id"
)
private val idFields = setOf(
    "id", "uid", "game_uid", "avatar_id", "character_id", "weapon_id", "item_id", "reliquary_id", "role_id",
    "entry_page_id", "menu_id", "topic_id", "task_id", "award_id", "post_id", "reply_id",
    "ann_id", "version_id", "tag_id", "group_id", "lineup_id", "deck_id", "entity_key"
)
private val collectionFields = setOf(
    "list", "items", "results", "avatar_list", "articles", "awards", "task_list", "exchange_award_list", "game_roles_list"
)
private val htmlImage = Regex("""(?i)<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""")
private val dateFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.JAPAN)

@Composable
internal fun FeatureResult(
    result: JSONObject,
    method: String? = null,
    onOpen: ((ResultLink) -> Unit)? = null
) {
    val root = remember(result, method) {
        when (method) {
            "get_wiki_page" -> result.optJSONObject("page") ?: result
            "get_hoyolab_user" -> result.optJSONObject("user_info") ?: result
            "get_lineup_details" -> result.optJSONObject("lineup") ?: result
            "get_tcg_deck" -> result.optJSONObject("detail") ?: result
            "get_threshold_simulation" -> result.optJSONObject("void_front_battle_detail") ?: result
            "get_threshold_simulation_brief" -> result.optJSONObject("void_front_battle_abstract_info_brief") ?: result
            "get_accompany_character_details" -> result.optJSONObject("info") ?: result
            else -> result
        }
    }
    var translate by remember(root, method) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (method in translatableFeatures) {
            TextButton(onClick = { translate = !translate }) {
                Text(if (translate) "原文を表示" else "日本語に翻訳")
            }
        }
        CompositionLocalProvider(LocalJapaneseTranslation provides translate) {
            ResultObject(root, method, onOpen, depth = 0)
        }
    }
}

@Composable
private fun ResultObject(
    result: JSONObject,
    method: String?,
    onOpen: ((ResultLink) -> Unit)?,
    depth: Int
) {
    if (depth > 12) return
    val error = result.optString("_error")
    if (error.isNotBlank()) {
        Text(displayText(error), color = MaterialTheme.colorScheme.error)
        return
    }
    val title = remember(result) { titleFields.firstNotNullOfOrNull { stringValue(result.opt(it)) } }
    val portrait = remember(result) { portraitFields.firstNotNullOfOrNull { imageValue(result.opt(it)) } }
    val banner = remember(result) { bannerFields.firstNotNullOfOrNull { imageValue(result.opt(it)) } }
    val entries = remember(result, title, banner, portrait) {
        result.keys().asSequence().map { it to result.opt(it) }.filter { (key, value) ->
            value != JSONObject.NULL && value != null && key !in hiddenFields &&
                !(key in titleFields && stringValue(value) == title) &&
                !(key in portraitFields && imageValue(value) == portrait) &&
                !(key in bannerFields && banner != null && imageValue(value) == banner) &&
                (key !in idFields || title == null && key in setOf("uid", "id", "entry_page_id", "reply_id")) &&
                (value !is String || value.isNotBlank())
        }.toList()
    }
    val uriHandler = LocalUriHandler.current
    val links = remember(result, method) { if (onOpen == null) emptyList() else resultLinks(method, result) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        banner?.let { FeatureImage(it, wide = true) }
        if (title != null || portrait != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FeatureImage(portrait)
                title?.let {
                    if (method in translatableFeatures && result.keys().asSequence().none { key ->
                            key in setOf("nickname", "nick_name", "author_nickname")
                        }) JapaneseContent(it, MaterialTheme.typography.titleSmall)
                    else Text(displayText(it), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        for ((key, value) in entries) {
            when (value) {
                is JSONObject -> {
                    if (value.length() > 0) ResultGroup(fieldLabel(key), depth == 0 && method != "battle") {
                        ResultObject(value, method, onOpen, depth + 1)
                    }
                }
                is JSONArray -> {
                    if (value.length() == 0) continue
                    if (key in collectionFields) {
                        ResultList(value, method, onOpen, depth + 1)
                    } else {
                        val label = if (key == "roles" && method == "get_tcg_cards") "キャラクターカード" else fieldLabel(key)
                        ResultGroup(label, depth == 0 && method != "battle") {
                            ResultList(value, method, onOpen, depth + 1)
                        }
                    }
                }
                null -> Unit
                else -> {
                    val url = (value as? String)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    if (url != null) {
                        val link = publicImageUrl(url)
                        if (link != null) {
                            TextButton(onClick = { runCatching { uriHandler.openUri(link) } }) { Text("公式ページを開く") }
                        }
                        continue
                    }
                    val text = formatValue(key, value, method)
                    if (text.isBlank()) continue
                    if (key in proseFields) {
                        JapaneseContent(text)
                        (value as? String)?.let { raw ->
                            htmlImage.findAll(raw).map { it.groupValues[1] }
                                .mapNotNull(::publicImageUrl).distinct().take(6).forEach { FeatureImage(it, wide = true) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(fieldLabel(key), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        for (link in links) {
            TextButton(onClick = { onOpen?.invoke(link) }) { Text(link.label) }
        }
        if (title == null && portrait == null && banner == null && entries.none { (_, value) ->
                value !is JSONArray || value.length() > 0
            }) {
            Text("表示できる項目がありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultGroup(label: String, initiallyOpen: Boolean, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(initiallyOpen) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(if (expanded) "−" else "+")
        }
        if (expanded) Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun ResultList(
    rows: JSONArray,
    method: String?,
    onOpen: ((ResultLink) -> Unit)?,
    depth: Int
) {
    if (depth > 12) return
    var visible by remember(rows) { mutableIntStateOf(15) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (index in 0 until minOf(rows.length(), visible)) {
            when (val item = rows.opt(index)) {
                is JSONObject -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        ResultObject(item, method, onOpen, depth)
                    }
                }
                is JSONArray -> ResultList(item, method, onOpen, depth + 1)
                is String -> {
                    val image = publicImageUrl(item)
                    if (image != null) FeatureImage(image, wide = true)
                    else JapaneseContent(item)
                }
                is Number -> Text(formatValue("", item, method))
            }
        }
        if (visible < rows.length()) {
            HorizontalDivider()
            TextButton(onClick = { visible += 15 }) { Text("さらに表示（残り ${rows.length() - visible} 件）") }
        }
    }
}

private fun imageValue(value: Any?): String? {
    val url = when (value) {
        is String -> value
        is JSONObject -> stringValue(value.opt("url")) ?: stringValue(value.opt("icon_url"))
        is JSONArray -> return (0 until value.length()).firstNotNullOfOrNull { imageValue(value.opt(it)) }
        else -> null
    }
    return url?.let(::publicImageUrl)
}

private fun stringValue(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }

private fun formatValue(key: String, value: Any?, method: String?): String {
    if (value is Boolean) return when (key) {
        "is_sign" -> if (value) "済み" else "未チェックイン"
        "is_public" -> if (value) "公開" else "非公開"
        "is_finished", "is_clear", "is_pass", "completed" -> if (value) "達成" else "未達成"
        else -> if (value) "はい" else "いいえ"
    }
    val text = value.toString()
    if (key == "status" && method == "get_mimo_tasks") {
        return mapOf("1" to "報酬を受取可能", "2" to "進行中", "3" to "受取済み")[text] ?: text
    }
    if (key == "status" && method == "get_mimo_shop_items") {
        return mapOf("1" to "交換可能", "2" to "交換不可", "3" to "交換上限", "4" to "在庫切れ")[text] ?: text
    }
    localizedApiValue(key, text, method)?.let { return it }
    if (key in dateFields || key.endsWith("_datetime") || key.endsWith("_date_time")) {
        val offset = runCatching { OffsetDateTime.parse(text.replace(' ', 'T')) }.getOrNull()
        if (offset != null) return dateFormat.format(offset.atZoneSameInstant(ZoneId.of("Asia/Tokyo")))
        val number = text.toLongOrNull() ?: 0L
        val instant = when (number) {
            in 946684800L..4102444800L -> Instant.ofEpochSecond(number)
            in 946684800000L..4102444800000L -> Instant.ofEpochMilli(number)
            else -> null
        }
        if (instant != null) return dateFormat.format(instant.atZone(ZoneId.of("Asia/Tokyo")))
    }
    if (value is Number && key !in idFields && !key.endsWith("_id")) {
        return NumberFormat.getNumberInstance(Locale.JAPAN).format(value)
    }
    return displayText(text)
}

private fun resultLinks(method: String?, item: JSONObject): List<ResultLink> {
    fun link(label: String, target: String, argument: String, vararg keys: String): List<ResultLink> {
        val id = keys.firstNotNullOfOrNull { key ->
            item.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank)
        } ?: return emptyList()
        return listOf(ResultLink(label, target, mapOf(argument to id)))
    }
    return when (method) {
        "get_wiki_previews" -> link("記事を読む", "get_wiki_page", "entry_page_id", "entry_page_id", "id")
        "get_lineups", "get_user_lineups", "get_favorite_lineups" ->
            link("編成を見る", "get_lineup_details", "id", "lineup_id", "id")
        "teapot_replicas" -> {
            val blueprint = item.optJSONObject("blueprint") ?: return emptyList()
            if (blueprint.optBoolean("is_invalid")) return emptyList()
            val code = blueprint.optString("share_code").takeIf(String::isNotBlank) ?: return emptyList()
            listOf(ResultLink("必要な調度品を見る", "get_teapot_replica_blueprint",
                mapOf("share_code" to code, "region" to blueprint.optString("region"))))
        }
        "get_tcg_decks" -> link("デッキを見る", "get_tcg_deck", "id", "id", "deck_id")
        "get_tcg_cards" -> link("効果を見る", "get_tcg_card_effect", "id", "id")
        "get_tcg_deck" -> link("コメントを見る", "get_tcg_deck_comments", "entity_key", "entity_key")
        "get_accompany_characters" -> link("コンパニオンを見る", "get_accompany_character_details", "topic_id", "topic_id")
        "get_accompany_character_details" -> link("投稿を見る", "get_accompany_character_posts", "topic_id", "topic_id")
        "search_users" -> link("プロフィールを見る", "get_hoyolab_user", "uid", "uid")
        "get_zzz_upgrade_guide_agents" -> link("育成ガイドを見る", "get_zzz_agent_upgrade_guide", "avatar_id", "id", "avatar_id")
        else -> emptyList()
    }
}

private val dateFields = setOf(
    "time", "start_time", "begin_time", "end_time", "create_time", "timestamp", "created_at", "updated_at",
    "match_time", "challenge_time", "floor_challenge_time", "hadal_begin_time", "hadal_end_time"
)
private val translatableFeatures = setOf(
    "get_wiki_previews", "get_wiki_page", "get_wiki_pages", "get_genshin_announcements",
    "get_starrail_announcements", "get_zzz_announcements", "get_accompany_character_posts",
    "get_accompany_character_details", "get_web_events", "get_tcg_decks", "get_tcg_deck",
    "get_tcg_deck_comments", "get_tcg_card_effect", "get_lineups", "get_lineup_details", "teapot_replicas"
)
