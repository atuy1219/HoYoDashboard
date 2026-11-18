package com.atuy.hoyodashboard.api

import org.json.JSONArray
import org.json.JSONObject

sealed interface GameProfile {
    val nickname: String
    val level: Int
    val raw: JSONObject
}

data class GenshinProfile(
    override val nickname: String,
    override val level: Int,
    val activeDays: Int,
    val achievements: Int,
    val avatarCount: Int,
    val spiralAbyss: String?,
    override val raw: JSONObject
) : GameProfile

data class StarRailProfile(
    override val nickname: String,
    override val level: Int,
    val activeDays: Int,
    val achievements: Int,
    val avatarCount: Int,
    val forgottenHall: String?,
    override val raw: JSONObject
) : GameProfile

data class ZzzProfile(
    override val nickname: String,
    override val level: Int,
    val achievementCount: Int,
    val agentCount: Int,
    val bangbooCount: Int,
    override val raw: JSONObject
) : GameProfile

data class HonkaiProfile(
    override val nickname: String,
    override val level: Int,
    val battlesuitCount: Int,
    override val raw: JSONObject
) : GameProfile

data class CharacterPreview(
    val id: Int,
    val name: String,
    val level: Int,
    val rarity: Int,
    val icon: String?,
    val element: String?,
    val weapon: String?,
    val raw: JSONObject
) {
    companion object {
        fun from(value: JSONObject) = CharacterPreview(
            id = value.int("id", "avatar_id", "role_id"),
            name = value.string("name", "avatar_name", "nickname"),
            level = value.int("level", "avatar_level"),
            rarity = value.int("rarity", "rank_type", "star"),
            icon = value.stringOrNull("icon", "avatar_icon", "avatar_url", "avatar_square_icon", "square_icon", "image"),
            element = value.stringOrNull("element", "element_name"),
            weapon = value.stringOrNull("weapon_name", "weapon"),
            raw = value
        )
    }
}

data class GachaRecord(
    val id: String,
    val uid: String?,
    val name: String,
    val itemType: String?,
    val rarity: Int,
    val time: String,
    val timestamp: Long,
    val timezoneOffset: Int,
    val bannerType: Int,
    val bannerId: String?,
    val itemId: String?,
    val isUp: Boolean?,
    val raw: JSONObject
) {
    companion object {
        fun from(value: JSONObject, bannerType: Int = 0): GachaRecord {
            val time = value.string("time")
            val timezoneOffset = value.int("tz_offset")
            val timestamp = runCatching {
                java.time.LocalDateTime.parse(time.replace(" ", "T"))
                    .toInstant(java.time.ZoneOffset.ofHours(8 + timezoneOffset))
                    .toEpochMilli()
            }.getOrDefault(0L)
            return GachaRecord(
                id = value.string("id"),
                uid = value.stringOrNull("uid"),
                name = value.string("name", "item_name"),
                itemType = value.stringOrNull("item_type"),
                rarity = value.int("rank_type", "rarity"),
                time = time,
                timestamp = timestamp,
                timezoneOffset = timezoneOffset,
                bannerType = value.int("gacha_type", "op_gacha_type", "real_gacha_type").takeIf { it > 0 } ?: bannerType,
                bannerId = value.stringOrNull("gacha_id", "schedule_id", "banner_id"),
                itemId = value.stringOrNull("item_id"),
                isUp = when (val up = value.opt("is_up")) {
                    is Boolean -> up
                    is Number -> up.toInt() != 0
                    is String -> when (up.lowercase()) {
                        "true", "1" -> true
                        "false", "0" -> false
                        else -> null
                    }
                    else -> null
                },
                raw = value
            )
        }
    }
}

data class DiaryCategory(
    val id: String?,
    val name: String,
    val amount: Long,
    val percentage: Int?
)

data class DiarySummary(
    val month: String?,
    val currentPrimogems: Long?,
    val currentMora: Long?,
    val currentStellarJades: Long?,
    val currentCredits: Long?,
    val currentHcoin: Long?,
    val currentRailsPass: Long?,
    val currentPolychromes: Long?,
    val currentMasterTapes: Long?,
    val currentBoopons: Long?,
    val categories: List<DiaryCategory>,
    val raw: JSONObject
)

internal fun mergeHistoryByTime(
    groups: List<List<JSONObject>>,
    limit: Int?
): List<JSONObject> {
    val merged = groups.asSequence().flatten()
        .sortedByDescending { it.optString("time") }
        .toList()
    return if (limit != null && limit > 0) merged.take(limit) else merged
}

data class RewardItem(
    val name: String,
    val icon: String?,
    val amount: Int,
    val raw: JSONObject
)

data class TransactionItem(
    val id: String,
    val time: String,
    val action: String,
    val amount: Long,
    val balance: Long?,
    val raw: JSONObject
)

private fun JSONObject.string(vararg keys: String): String =
    keys.firstNotNullOfOrNull { key ->
        opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
    } ?: ""

private fun JSONObject.stringOrNull(vararg keys: String): String? =
    string(*keys).takeIf { it.isNotBlank() }

private fun JSONObject.int(vararg keys: String): Int =
    keys.firstNotNullOfOrNull { key -> opt(key)?.toString()?.toIntOrNull() } ?: 0

private fun JSONObject.longOrNull(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { key -> opt(key)?.toString()?.toLongOrNull() }

object UpstreamModelParser {
    fun profile(game: ApiGame, raw: JSONObject): GameProfile {
        val role = raw.optJSONObject("role") ?: raw.optJSONObject("info") ?: JSONObject()
        val stats = raw.optJSONObject("stats") ?: raw
        val avatars = raw.optJSONArray("avatars") ?: raw.optJSONArray("avatar_list")
            ?: raw.optJSONArray("list") ?: JSONArray()
        val nickname = role.string("nickname", "name").ifBlank {
            raw.string("nickname", "name")
        }
        val level = role.int("level", "player_level").takeIf { it > 0 }
            ?: stats.int("level", "player_level")
        return when (game) {
            ApiGame.GENSHIN -> GenshinProfile(
                nickname, level,
                stats.int("active_days", "active_days_count"),
                stats.int("achievements", "achievement_count"),
                avatars.length(),
                stats.stringOrNull("spiral_abyss", "spiral_abyss_floor"),
                raw
            )
            ApiGame.STARRAIL -> StarRailProfile(
                nickname, level,
                stats.int("active_days", "active_days_count"),
                stats.int("achievements", "achievement_count"),
                avatars.length(),
                stats.stringOrNull("abyss_process", "forgotten_hall"),
                raw
            )
            ApiGame.ZZZ -> {
                val agents = raw.optJSONArray("avatar_list") ?: avatars
                val bangboos = raw.optJSONArray("buddy_list") ?: raw.optJSONArray("bangboo_list") ?: JSONArray()
                ZzzProfile(
                    nickname, level,
                    stats.int("achievement_count", "achievements"),
                    agents.length(), bangboos.length(), raw
                )
            }
            ApiGame.HONKAI -> HonkaiProfile(
                nickname, level,
                raw.optJSONArray("characters")?.length() ?: avatars.length(),
                raw
            )
            ApiGame.TOT -> throw UnsupportedOperationException("No profile model is defined for Tears of Themis.")
        }
    }

    fun characters(raw: JSONObject): List<CharacterPreview> {
        val list = raw.optJSONArray("list") ?: raw.optJSONArray("avatar_list")
            ?: raw.optJSONArray("avatars") ?: raw.optJSONArray("characters")
            ?: raw.optJSONArray("role_list") ?: JSONArray()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(CharacterPreview::from) }
    }

    fun gachaHistory(raw: JSONObject, bannerType: Int): List<GachaRecord> {
        val list = raw.optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).mapNotNull {
            list.optJSONObject(it)?.let { item -> GachaRecord.from(item, bannerType) }
        }
    }

    fun diary(raw: JSONObject): DiarySummary {
        val monthData = raw.optJSONObject("month_data") ?: raw.optJSONObject("data") ?: raw
        val income = monthData.optJSONObject("income") ?: JSONObject()
        val currencies = income.optJSONArray("list") ?: monthData.optJSONArray("list") ?: JSONArray()
        val currencyValues = linkedMapOf<String, Long>()
        for (index in 0 until currencies.length()) {
            val item = currencies.optJSONObject(index) ?: continue
            val type = item.string("data_type", "type")
            val name = item.string("data_name", "name")
            val amount = item.longOrNull("count", "num", "amount") ?: 0L
            if (type.isNotBlank()) currencyValues[type] = amount
            if (name.isNotBlank()) currencyValues[name] = amount
        }
        val categoryRows = monthData.optJSONArray("group_by") ?: monthData.optJSONArray("income_components")
            ?: income.optJSONArray("income_components") ?: JSONArray()
        val categories = (0 until categoryRows.length()).mapNotNull { index ->
            val item = categoryRows.optJSONObject(index) ?: return@mapNotNull null
            DiaryCategory(
                id = item.stringOrNull("action_id", "action", "data_type"),
                name = localizeDiarySource(item.string("action_name", "data_name", "name", "action")),
                amount = item.longOrNull("num", "count", "amount") ?: 0L,
                percentage = item.opt("percent")?.toString()?.toIntOrNull()
            )
        }
        return DiarySummary(
            month = raw.stringOrNull("data_month", "month", "month_str", "current_month"),
            currentPrimogems = monthData.longOrNull("current_primogems"),
            currentMora = monthData.longOrNull("current_mora"),
            currentStellarJades = monthData.longOrNull("current_stellar_jades"),
            currentCredits = monthData.longOrNull("current_credits"),
            currentHcoin = monthData.longOrNull("current_hcoin"),
            currentRailsPass = monthData.longOrNull("current_rails_pass"),
            currentPolychromes = currencyValues["PolychromesData"] ?: currencyValues["Polychromes"],
            currentMasterTapes = currencyValues["MatserTapeData"] ?: currencyValues["MasterTapeData"],
            currentBoopons = currencyValues["BooponsData"] ?: currencyValues["Boopons"],
            categories = categories,
            raw = raw
        )
    }

    fun rewards(raw: JSONObject): List<RewardItem> {
        val list = raw.optJSONArray("list") ?: raw.optJSONArray("awards") ?: JSONArray()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            RewardItem(
                item.string("name", "award_name"),
                item.stringOrNull("icon", "icon_url"),
                item.int("cnt", "count", "amount"),
                item
            )
        }
    }

    fun transactions(raw: JSONObject): List<TransactionItem> {
        val list = raw.optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            TransactionItem(
                item.string("id"),
                item.string("time"),
                item.string("action", "action_name", "reason"),
                item.longOrNull("amount", "num") ?: 0L,
                item.longOrNull("balance"),
                item
            )
        }
    }
}

fun localizeDiarySource(source: String): String = when (source) {
    "daily_activity_rewards" -> "デイリー報酬"
    "growth_rewards" -> "育成報酬"
    "shiyu_rewards" -> "式輿防衛"
    "mail_rewards" -> "メール報酬"
    "hollow_rewards" -> "零号ホロウ"
    "event_rewards" -> "イベント報酬"
    "other_rewards" -> "その他"
    else -> source
}
