package com.atuy.hoyodashboard.api

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class WarpItem(val id: Long, val name: String, val icon: String?, val type: String, val rarity: Int)
data class WarpBriefEntry(val item: WarpItem, val count: Int)
data class WarpBrief(val versionId: Int, val items: List<WarpBriefEntry>)
data class WarpPool(val id: Long, val version: String, val name: String, val totalCount: Int, val featuredCount: Int, val featured: WarpItem?)
data class FiveStarWarp(val id: String, val item: WarpItem, val featured: Boolean, val count: Int)
data class FiveStarWarpHistory(val versionId: Int, val pity: Int, val warps: List<FiveStarWarp>)

data class CheckInStatus(val signedIn: Boolean, val claimedDays: Int)
data class AccountCard(val uid: String?, val gameId: Int, val nickname: String, val level: Int?, val serverName: String?)

data class CharacterStat(val name: String, val value: String?, val recommended: Boolean)
data class CharacterEquipment(val id: Long, val name: String, val level: Int, val icon: String?, val stats: List<CharacterStat>)
data class CharacterSkill(val id: Int, val name: String, val level: Int?, val description: String?, val icon: String?)
data class CharacterBuild(
    val character: CharacterPreview,
    val weapon: CharacterEquipment?,
    val equipment: List<CharacterEquipment>,
    val skills: List<CharacterSkill>,
    val stats: List<CharacterStat>,
    val raw: JSONObject
)

internal fun characterBuild(data: JSONObject): CharacterBuild {
    val gear = listOf("relics", "ornaments", "stigmatas", "discs", "equip_list").flatMap {
        data.optJSONArray(it).objects()
    }
    val skills = (data.optJSONArray("skills") ?: data.optJSONArray("skill_list")).objects()
    return CharacterBuild(
        CharacterPreview.from(data),
        (data.optJSONObject("weapon") ?: data.optJSONObject("equip") ?: data.optJSONObject("w_engine"))
            ?.let(::equipment),
        gear.map(::equipment),
        skills.map { row ->
            CharacterSkill(row.optInt("skill_id", row.optInt("id")), row.optString("name"),
                row.optInt("level").takeIf { it > 0 }, row.optString("desc").ifBlank { null },
                row.optString("icon").ifBlank { null })
        },
        parseStats(data, listOf("properties", "selected_properties", "base_properties", "extra_properties", "element_properties")),
        data
    )
}

private fun equipment(data: JSONObject) = CharacterEquipment(
    data.optLong("id", data.optLong("equip_id")), data.optString("name"), data.optInt("level"),
    data.optString("icon").ifBlank { null },
    parseStats(data, listOf("main_property", "sub_property", "sub_property_list", "properties", "sub_properties"))
)

private fun parseStats(data: JSONObject, fields: List<String>): List<CharacterStat> = fields.flatMap { field ->
    when (val value = data.opt(field)) {
        is JSONObject -> listOf(value)
        is JSONArray -> value.objects()
        else -> emptyList()
    }
}.mapNotNull { stat ->
    val name = stat.optString("name").ifBlank { stat.optJSONObject("info")?.optString("name").orEmpty() }
    if (name.isBlank()) null else CharacterStat(name, stat.optString("value").ifBlank { stat.optString("final").ifBlank { null } },
        stat.optBoolean("recommended"))
}

private fun JSONObject.warpItem() = WarpItem(
    optLong("item_id", optLong("id")), optString("name"), optString("icon").ifBlank { null },
    optString("item_type"), optInt("rarity")
)

internal fun JSONObject.warpBrief() = WarpBrief(optInt("version_id"), optJSONArray("items").objects().mapNotNull {
    it.optJSONObject("item")?.let { item -> WarpBriefEntry(item.warpItem(), it.optInt("count")) }
})

internal fun JSONObject.warpPools(): List<WarpPool> = optJSONArray("list").objects().map {
    WarpPool(it.optLong("gacha_id"), it.optString("version"), it.optString("pool_name"),
        it.optInt("total_count"), it.optInt("up_count"), it.optJSONObject("up_item")?.warpItem())
}

internal fun JSONObject.fiveStarHistory() = FiveStarWarpHistory(
    optInt("version_id"), optInt("pity"), optJSONArray("warps").objects().mapNotNull {
        it.optJSONObject("item")?.let { item ->
            FiveStarWarp(it.optString("id"), item.warpItem(), it.optBoolean("is_up"), it.optInt("gacha_count"))
        }
    }
)

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

internal fun normalizeUpstreamResponse(
    method: String,
    response: JSONObject,
    account: ApiAccount,
    raw: Boolean,
    simple: Boolean = false
): JSONObject {
    fun rows(values: JSONArray?) = JSONObject().put("list", values ?: JSONArray())
    fun rows(values: List<JSONObject>) = JSONObject().put("list", JSONArray(values))
    val normalized = when (method) {
        "get_honkai_battlesuits" -> rows(response.optJSONArray("characters").objects().map { entry ->
            val character = entry.optJSONObject("character") ?: entry
            JSONObject(character.toString()).apply {
                character.optJSONObject("avatar")?.let { avatar -> avatar.keys().forEach { key -> put(key, avatar.get(key)) } }
                val stigmata = optJSONArray("stigmatas").objects().filter { it.optLong("id") > 0 }
                if (has("stigmatas")) put("stigmatas", JSONArray(stigmata))
            }
        })
        "get_honkai_old_abyss", "get_honkai_superstring_abyss", "get_honkai_memorial_arena" ->
            rows(response.optJSONArray("reports"))
        "get_honkai_elysian_realm" -> rows(response.optJSONArray("records"))
        "get_zzz_event_calendar" -> rows(response.optJSONArray("activity_list"))
        "get_wiki_pages" -> rows(response.optJSONArray("entry_pages"))
        "get_wiki_previews" -> rows(response.optJSONArray("list").objects().filter { it.optString("icon_url").isNotBlank() })
        "get_monthly_rewards" -> rows(response.optJSONArray("awards"))
        "get_complete_artifact_set" -> rows(response.optJSONArray("reliquary_list"))
        "get_tcg_deck_labels" -> rows(response.optJSONArray("tree")?.optJSONObject(0)?.optJSONArray("children"))
        "get_starrail_characters" -> starRailBuildFields(response, simple)
        "get_starrail_warp_pool_stats" -> rows(response.optJSONArray("cards"))
        "get_stygian_onslaught" -> rows(response.optJSONArray("data").objects().filter {
            raw || it.optJSONObject("schedule")?.optBoolean("is_valid") == true
        })
        "get_threshold_simulation", "get_threshold_simulation_raw" ->
            response.optJSONObject("void_front_battle_detail") ?: response
        "get_threshold_simulation_brief" -> response.optJSONObject("void_front_battle_abstract_info_brief") ?: response
        "get_shiyu_defense" -> {
            val version = response.optString("hadal_ver")
            val value = response.optJSONObject("hadal_info_$version")
            if (value == null) response else JSONObject(value.toString()).apply {
                if (version == "v2") {
                    response.opt("nick_name")?.let { put("nick_name", it) }
                    response.opt("icon")?.let { put("icon", it) }
                }
            }
        }
        "get_mimo_games" -> {
            val games = response.optJSONArray("act_list") ?: response.optJSONArray("list")
            rows(games.objects().map { it.optJSONObject("act_info") ?: it })
        }
        "get_mimo_tasks" -> rows(response.optJSONArray("task_list"))
        "get_mimo_shop_items" -> rows(response.optJSONArray("exchange_award_list"))
        "get_accompany_characters" -> rows(response.optJSONArray("game_roles_list"))
        "get_accompany_voice_setting" -> response.optJSONObject("setting")?.optJSONObject("voice_script_setting") ?: response
        "search_users" -> rows(response.optJSONArray("list").objects().map { it.optJSONObject("user") ?: it })
        else -> response
    }
    val keys = responseDates[method] ?: return normalized
    if (raw && account.game != ApiGame.ZZZ) return normalized
    return withServerDates(normalized, keys, ZoneOffset.ofHours(account.offsetHours))
}

private val responseDates = mapOf(
    "get_genshin_spiral_abyss" to setOf("start_time", "end_time", "timestamp"),
    "get_genshin_spiral_abyss_previous" to setOf("start_time", "end_time", "timestamp"),
    "get_imaginarium_theater" to setOf("start_date_time", "end_date_time", "finish_date_time"),
    "get_genshin_tcg_matches" to setOf("match_time"),
    "get_stygian_onslaught" to setOf("start_time", "end_time"),
    "get_starrail_challenge" to setOf("begin_time", "end_time", "challenge_time"),
    "get_starrail_pure_fiction" to setOf("begin_time", "end_time", "challenge_time"),
    "get_starrail_apc_shadow" to setOf("begin_time", "end_time", "challenge_time", "last_update_time"),
    "get_anomaly_arbitration" to setOf("begin_time", "end_time", "challenge_time", "last_update_time"),
    "get_shiyu_defense" to setOf("hadal_begin_time", "hadal_end_time", "challenge_time", "floor_challenge_time"),
    "get_deadly_assault" to setOf("start_time", "end_time", "challenge_time"),
    "get_annihilation_simulacrum" to setOf("start_time", "end_time"),
    "get_threshold_simulation" to setOf("challenge_time"),
    "get_threshold_simulation_raw" to setOf("challenge_time")
)

private fun withServerDates(data: JSONObject, keys: Set<String>, offset: ZoneOffset): JSONObject {
    fun parse(value: Any): OffsetDateTime? = runCatching {
        val stamp = (value as? JSONObject)?.opt("timestamp") ?: value
        val number = stamp.toString().toLongOrNull()
        when {
            number != null && number > 0 -> {
                val time = if (number >= 100_000_000_000) Instant.ofEpochMilli(number) else Instant.ofEpochSecond(number)
                time.atOffset(offset)
            }
            value is JSONObject && value.has("year") -> LocalDateTime.of(
                value.getInt("year"), value.getInt("month"), value.getInt("day"),
                value.optInt("hour"), value.optInt("minute"), value.optInt("second")
            ).atOffset(offset)
            value is String -> runCatching { OffsetDateTime.parse(value.replace(' ', 'T')) }.getOrElse {
                LocalDateTime.parse(value.replace(' ', 'T')).atOffset(offset)
            }
            else -> null
        }
    }.getOrNull()

    fun convert(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().apply {
            value.keys().forEach { key ->
                val field = value.get(key)
                val date = if (key in keys) parse(field) else null
                put(key, date?.toString() ?: convert(field))
            }
        }
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) put(convert(value.opt(index)))
        }
        else -> value
    }
    return convert(data) as JSONObject
}

private fun starRailBuildFields(data: JSONObject, simple: Boolean): JSONObject {
    val output = JSONObject(data.toString())
    val properties = data.optJSONObject("property_info")
    val recommendations = data.optJSONObject("recommend_property")
    val wiki = data.optJSONObject("equip_wiki")
    for (character in output.optJSONArray("avatar_list").objects()) {
        val id = character.optString("id")
        val hasRecommendations = recommendations?.optJSONObject(id)
        fun ids(key: String): Set<String> {
            val array = hasRecommendations?.optJSONArray(key) ?: return emptySet()
            return (0 until array.length()).map(array::optString).toSet()
        }
        val recommended = ids("recommend_relic_properties")
        val preferred = ids("custom_relic_properties")
        fun fillStat(stat: JSONObject) {
            val type = stat.optString("property_type")
            properties?.optJSONObject(type)?.let { info ->
                stat.put("info", info)
                info.optString("name").takeIf(String::isNotBlank)?.let { stat.put("name", it) }
            }
            if (hasRecommendations != null) stat.put("recommended", type in recommended).put("preferred", type in preferred)
        }
        val equipment = character.optJSONArray("relics").objects() + character.optJSONArray("ornaments").objects()
        for (relic in equipment) {
            if (simple) {
                relic.remove("main_property")
                relic.remove("properties")
            } else {
                relic.optJSONObject("main_property")?.let(::fillStat)
                relic.optJSONArray("properties").objects().forEach(::fillStat)
            }
        }
        if (simple) {
            character.remove("skills")
            character.remove("properties")
            character.remove("servant_detail")
        } else {
            character.optJSONArray("properties").objects().forEach(::fillStat)
            character.optJSONObject("servant_detail")?.let { servant ->
                if (servant.optLong("servant_id") == 0L) character.remove("servant_detail")
                else servant.optJSONArray("servant_properties").objects().forEach(::fillStat)
            }
        }
        character.optJSONObject("equip")?.let { item ->
            wiki?.optString(item.optString("id"))?.takeIf(String::isNotBlank)?.let { item.put("wiki", it) }
        }
    }
    return output
}
