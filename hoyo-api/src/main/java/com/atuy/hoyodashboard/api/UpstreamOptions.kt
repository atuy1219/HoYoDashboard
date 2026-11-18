package com.atuy.hoyodashboard.api

import org.json.JSONArray
import org.json.JSONObject

internal fun ApiAccount.withUid(uid: String, serverOverride: String? = null): ApiAccount {
    require(uid.all(Char::isDigit) && uid.toLongOrNull()?.let { it > 0 } == true) { "ゲームのUIDを確認してください" }
    if (uid == this.uid && serverOverride == null) return this
    val server = serverOverride ?: when (game) {
        ApiGame.GENSHIN -> when (uid.dropLast(8)) {
            "1", "2", "3" -> "cn_gf01"
            "5" -> "cn_qd01"
            "6" -> "os_usa"
            "7" -> "os_euro"
            "8", "18" -> "os_asia"
            "9" -> "os_cht"
            else -> null
        }
        ApiGame.STARRAIL -> when (uid.dropLast(8)) {
            "1", "2" -> "prod_gf_cn"
            "5" -> "prod_qd_cn"
            "6" -> "prod_official_usa"
            "7" -> "prod_official_eur"
            "8" -> "prod_official_asia"
            "9" -> "prod_official_cht"
            else -> null
        }
        ApiGame.ZZZ -> if (uid.length == 8) "prod_gf_cn" else when (uid.dropLast(8)) {
            "10" -> "prod_gf_us"
            "13" -> "prod_gf_jp"
            "15" -> "prod_gf_eu"
            "17" -> "prod_gf_sg"
            else -> null
        }
        ApiGame.HONKAI, ApiGame.TOT -> this.server
    } ?: throw IllegalArgumentException("UIDからサーバーを判定できませんでした")
    val region = when (game) {
        ApiGame.HONKAI, ApiGame.TOT -> this.region
        else -> if (server.startsWith("cn_") || server.endsWith("_cn")) ApiRegion.CHINESE else ApiRegion.OVERSEAS
    }
    return copy(uid = uid, server = server, region = region)
}

internal val ApiAccount.offsetHours: Int
    get() = when (server) {
        "os_usa", "prod_official_usa", "prod_gf_us" -> -5
        "os_euro", "prod_official_eur", "prod_gf_eu" -> 1
        else -> 8
    }

internal fun intList(value: String): List<Int> =
    if (value.trim().startsWith("[")) {
        val array = JSONArray(value)
        (0 until array.length()).map(array::getInt)
    } else value.split(',').filter(String::isNotBlank).map { it.trim().toInt() }

internal fun stringList(value: String): List<String> =
    if (value.trim().startsWith("[")) {
        val array = JSONArray(value)
        (0 until array.length()).map(array::getString)
    } else value.split(',').map(String::trim).filter(String::isNotEmpty)

internal fun agentGuideSelection(ids: List<Int>): String {
    require(ids.size in 1..10 && ids.all { it > 0 }) { "エージェントは一度に1〜10人まで選択できます" }
    return JSONArray(ids.map { id ->
        JSONObject().put("avatar_id", id).put("is_teaser", false)
            .put("teaser_need_weapon", false).put("teaser_sp_skill", false)
    }).toString()
}

internal fun upstreamOptions(name: String, arguments: Map<String, String>): Map<String, String> {
    val values = arguments.toMutableMap()
    fun rename(source: String, target: String, convert: (String) -> String = { it }) {
        values.remove(source)?.let { values.putIfAbsent(target, convert(it)) }
    }
    fun numbers(source: String, target: String) = rename(source, target) { JSONArray(intList(it)).toString() }
    fun selected(source: String): Boolean = values.remove(source)?.toBooleanStrictOrNull() == true

    rename("banner_type", "gacha_type")
    if (name in periodMethods) rename("previous", "schedule_type") { if (it == "true") "2" else "1" }
    values.remove("previous")
    when (name) {
        "get_record_cards", "get_hoyolab_user" -> rename("hoyolab_id", "uid")
        "get_wiki_previews" -> rename("menu", "menu_id")
        "get_wiki_page" -> rename("id", "entry_page_id")
        "get_wiki_pages" -> numbers("ids", "entry_page_ids")
        "get_lineup_details" -> rename("lineup", "id")
        "get_tcg_card_effect" -> rename("card", "id")
        "get_tcg_deck" -> rename("deck", "id")
        "get_tcg_deck_comments" -> rename("deck", "entity_key")
        "get_tcg_decks" -> {
            rename("label", "label_id")
            numbers("characters", "role_card_id")
            if (selected("newest")) values.putIfAbsent("order", "O_LATEST")
        }
        "get_lineups" -> {
            rename("scenario", "tag_id")
            numbers("characters", "roles")
            val match = selected("match_characters")
            val newest = selected("newest")
            if (match || newest) values.putIfAbsent("order", if (match) "Match" else "CreatedTime")
        }
        "get_genshin_detailed_characters" -> numbers("characters", "character_ids")
        "get_zzz_agent_upgrade_guide" -> rename("character_id", "avatar_list") { agentGuideSelection(intList(it)) }
        "get_threshold_simulation" -> rename("id", "void_front_id")
        "get_character_details" -> rename("character", "avatar_id")
        "get_complete_artifact_set" -> rename("artifact", "reliquary_id")
        "get_calculator_characters", "get_owned_calculator_characters" -> {
            numbers("elements", "element_attr_ids")
            numbers("weapon_types", "weapon_cat_ids")
            values.remove("include_traveler") // The pinned upstream accepts this option without sending it.
        }
        "get_calculator_weapons" -> {
            numbers("types", "weapon_cat_ids")
            numbers("rarities", "weapon_levels")
        }
        "get_calculator_artifacts" -> {
            rename("pos", "reliquary_cat_id")
            numbers("rarities", "reliquary_levels")
        }
        "get_calculator_furnishings" -> {
            rename("types", "cat_id")
            rename("rarities", "weapon_levels")
        }
        "get_accompany_character_posts" -> {
            rename("tab", "scene") {
                when (it) {
                    "fan_works" -> "1"
                    "discussions" -> "2"
                    else -> throw IllegalArgumentException("投稿の種類が無効です")
                }
            }
            rename("sort", "list_type")
            values.remove("previous_page")?.let { page ->
                val previous = JSONObject(page)
                for (key in listOf("last_id", "client_param", "session_param")) {
                    values.putIfAbsent(key, previous.optString(key))
                }
                values.putIfAbsent("is_first_brush", "false")
            }
        }
        "get_mimo_point_history" -> rename("operation", "operator_type")
        "get_zzz_diary_detail" -> rename("page", "current_page")
    }
    if (name.startsWith("get_calculator_") || name == "get_owned_calculator_characters") {
        val query = values.remove("query")
        if (!query.isNullOrBlank()) {
            require(values.keys.none { it in calculatorFilters && values[it] !in setOf("", "[]", "0") }) {
                "キーワード検索と絞り込みは同時に指定できません"
            }
            values["keywords"] = query
        }
    }
    return values
}

private val calculatorFilters = setOf("element_attr_ids", "weapon_cat_ids", "weapon_levels", "reliquary_levels", "reliquary_cat_id", "cat_id")
private val periodMethods = setOf(
    "get_genshin_spiral_abyss", "get_starrail_challenge", "get_starrail_pure_fiction", "get_starrail_apc_shadow",
    "get_shiyu_defense", "get_deadly_assault", "get_annihilation_simulacrum",
    "get_threshold_simulation", "get_threshold_simulation_brief"
)

internal val starRailWarpTypes = mapOf(
    "1" to "GachaType_Standard", "2" to "GachaType_Newbie",
    "11" to "GachaType_AvatarUp", "12" to "GachaType_EquipmentUp",
    "21" to "GachaType_CollabAvatarUp", "22" to "GachaType_CollabEquipmentUp"
)


internal fun decodeHistoryAuthKey(input: String): String {
    val token = if (input.startsWith("https://") || input.startsWith("http://")) {
        val uri = java.net.URI(input)
        uri.rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') == "authkey" }
            ?.substringAfter('=', "")?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("履歴リンクに取得キーがありません")
    } else input
    return java.net.URLDecoder.decode(token.replace("+", "%2B"), "UTF-8")
}
