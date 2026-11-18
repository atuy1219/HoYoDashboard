package com.atuy.hoyodashboard.api

import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

class UpstreamFeatureClient(
    private val api: UpstreamApiClient,
    private val cookie: String,
    val account: ApiAccount
) {
    fun availableMethods(): List<ApiMethod> = api.available(account.game)

    suspend fun raw(
        method: String,
        arguments: Map<String, String> = emptyMap()
    ): JSONObject = api.call(cookie, account, method, arguments)

    suspend fun profile(): GameProfile {
        val method = when (account.game) {
            ApiGame.GENSHIN -> "get_genshin_user"
            ApiGame.STARRAIL -> "get_starrail_user"
            ApiGame.ZZZ -> "get_zzz_user"
            ApiGame.HONKAI -> "get_honkai_user"
            ApiGame.TOT -> throw UnsupportedOperationException("Tears of Themis has no Chronicle profile API.")
        }
        return UpstreamModelParser.profile(account.game, raw(method))
    }

    suspend fun characters(): List<CharacterPreview> {
        val method = when (account.game) {
            ApiGame.GENSHIN -> "get_genshin_characters"
            ApiGame.STARRAIL -> "get_starrail_characters"
            ApiGame.ZZZ -> "get_zzz_agents"
            ApiGame.HONKAI -> "get_honkai_battlesuits"
            ApiGame.TOT -> throw UnsupportedOperationException("Tears of Themis has no character Chronicle API.")
        }
        return UpstreamModelParser.characters(raw(method))
    }

    suspend fun detailedCharacters(): List<JSONObject> {
        val method = when (account.game) {
            ApiGame.GENSHIN -> "get_genshin_detailed_characters"
            ApiGame.STARRAIL -> "get_starrail_characters"
            ApiGame.ZZZ -> "get_zzz_agent_info"
            ApiGame.HONKAI -> "get_honkai_battlesuits"
            ApiGame.TOT -> throw UnsupportedOperationException("Tears of Themis has no character Chronicle API.")
        }
        return raw(method).jsonList()
    }

    suspend fun fullBattleRecord(): JSONObject = when (account.game) {
        ApiGame.GENSHIN -> raw("get_full_genshin_user")
        ApiGame.STARRAIL -> JSONObject()
            .put("user", raw("get_starrail_user"))
            .put("forgotten_hall", raw("get_starrail_challenge"))
            .put("simulated_universe", raw("get_starrail_rogue"))
            .put("pure_fiction", raw("get_starrail_pure_fiction"))
            .put("apocalyptic_shadow", raw("get_starrail_apc_shadow"))
        ApiGame.ZZZ -> JSONObject()
            .put("user", raw("get_zzz_user"))
            .put("shiyu_defense", raw("get_shiyu_defense"))
            .put("deadly_assault", raw("get_deadly_assault"))
            .put("annihilation_simulacrum", raw("get_annihilation_simulacrum"))
            .put("lost_void", raw("get_lost_void_summary"))
        ApiGame.HONKAI -> raw("get_full_honkai_user")
        ApiGame.TOT -> throw UnsupportedOperationException("No Battle Chronicle exists for Tears of Themis.")
    }

    suspend fun gachaHistory(limit: Int? = null): List<GachaRecord> {
        val method = when (account.game) {
            ApiGame.GENSHIN -> "wish_history"
            ApiGame.STARRAIL -> "warp_history"
            ApiGame.ZZZ -> "signal_history"
            else -> throw UnsupportedOperationException("Gacha history is not available for " + account.game)
        }
        val args = limit?.takeIf { it > 0 }?.let { mapOf("limit" to it.toString()) } ?: emptyMap()
        val response = raw(method, args)
        return UpstreamModelParser.gachaHistory(response, 0)
    }

    suspend fun transactionHistory(limit: Int? = null): List<TransactionItem> {
        require(account.game == ApiGame.GENSHIN) {
            "Transaction history is currently available only for Genshin Impact."
        }
        val args = limit?.takeIf { it > 0 }?.let { mapOf("limit" to it.toString()) } ?: emptyMap()
        return UpstreamModelParser.transactions(raw("transaction_log", args))
    }

    suspend fun monthlyDiary(month: String? = null): DiarySummary {
        val method = when (account.game) {
            ApiGame.GENSHIN -> "get_genshin_diary"
            ApiGame.STARRAIL -> "get_starrail_diary"
            ApiGame.ZZZ -> "get_zzz_diary"
            else -> throw UnsupportedOperationException("Monthly ledger is not available for " + account.game)
        }
        val args = when (account.game) {
            ApiGame.GENSHIN -> month?.toIntOrNull()?.let { mapOf("month" to it.toString()) } ?: emptyMap()
            ApiGame.STARRAIL, ApiGame.ZZZ -> month?.let { mapOf("month" to it) } ?: emptyMap()
            else -> emptyMap()
        }
        return UpstreamModelParser.diary(raw(method, args))
    }

    suspend fun monthlyDiaryEntries(month: String, type: Int = 1): List<JSONObject> {
        val method = when (account.game) {
            ApiGame.GENSHIN -> "genshin_diary_log"
            ApiGame.STARRAIL -> "starrail_diary_log"
            ApiGame.ZZZ -> "get_zzz_diary_detail"
            else -> throw UnsupportedOperationException("Monthly transaction details are not available for " + account.game)
        }
        val monthValue = if (account.game == ApiGame.GENSHIN) {
            month.toIntOrNull()?.toString() ?: throw IllegalArgumentException("Genshin month must be 1-12.")
        } else month
        val response = raw(method, mapOf("month" to monthValue, "type" to type.toString()))
        return response.jsonList()
    }

    suspend fun rewardStatus(): JSONObject = raw("get_reward_info")

    suspend fun monthlyRewards(): List<RewardItem> =
        UpstreamModelParser.rewards(raw("get_monthly_rewards"))

    suspend fun claimedRewards(): List<RewardItem> =
        UpstreamModelParser.rewards(raw("claimed_rewards"))

    suspend fun announcements(): List<JSONObject> {
        val method = when (account.game) {
            ApiGame.GENSHIN -> "get_genshin_announcements"
            ApiGame.STARRAIL -> "get_starrail_announcements"
            ApiGame.ZZZ -> "get_zzz_announcements"
            else -> throw UnsupportedOperationException("Announcements are not available for " + account.game)
        }
        return raw(method).jsonList()
    }

    suspend fun genshinBannerIds(): List<String> {
        require(account.game == ApiGame.GENSHIN)
        val rows = raw("get_genshin_banner_ids").optJSONArray("list") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { rows.optString(it).takeIf(String::isNotBlank) }
    }

    suspend fun bannerNames(): Map<Int, String> {
        require(account.game == ApiGame.GENSHIN)
        val result = raw("get_genshin_banner_names")
        return result.keys().asSequence().mapNotNull { key ->
            key.toIntOrNull()?.let { it to result.optString(key) }
        }.toMap()
    }

    suspend fun bannerDetails(ids: List<String>? = null): List<JSONObject> {
        require(account.game == ApiGame.GENSHIN || account.game == ApiGame.STARRAIL)
        val args = ids?.let { mapOf("banner_ids" to JSONArray(it).toString()) } ?: emptyMap()
        return raw("get_banner_details", args).jsonList()
    }

    suspend fun accompanyPosts(topicId: Long, scene: Int = 1, listType: String = "hot"): List<JSONObject> =
        raw(
            "get_accompany_character_posts",
            mapOf("topic_id" to topicId.toString(), "scene" to scene.toString(), "list_type" to listType)
        ).jsonList()

    suspend fun tcgCards(): List<JSONObject> {
        require(account.game == ApiGame.GENSHIN)
        return raw("get_tcg_cards").jsonList()
    }

    suspend fun tcgDecks(): List<JSONObject> {
        require(account.game == ApiGame.GENSHIN)
        return raw("get_tcg_decks").jsonList()
    }

    suspend fun lineup(arguments: Map<String, String> = emptyMap()): List<JSONObject> {
        val method = when (account.game) {
            ApiGame.GENSHIN -> "get_lineups"
            ApiGame.STARRAIL -> "get_starrail_lineups"
            else -> throw UnsupportedOperationException("Lineup search is not available for " + account.game)
        }
        return raw(method, arguments).jsonList()
    }

    suspend fun wikiPreviews(menuId: String): List<JSONObject> =
        raw("get_wiki_previews", mapOf("menu_id" to menuId)).jsonList()

    suspend fun wikiPage(entryPageId: String): JSONObject =
        raw("get_wiki_page", mapOf("entry_page_id" to entryPageId))

    suspend fun mimoPointCount(): Int = raw("get_mimo_point_count").optInt("point")

    suspend fun zzzUpgradeGuides(): List<JSONObject> {
        require(account.game == ApiGame.ZZZ)
        return raw("get_all_zzz_agent_upgrade_guides").jsonList()
    }

    fun pages(
        method: String,
        arguments: Map<String, String> = emptyMap(),
        spec: PageSpec = PageSpec()
    ): Flow<JSONObject> {
        val definition = UpstreamCatalog.find(method)
            ?: throw IllegalArgumentException("Unknown upstream method: " + method)
        return UpstreamPaginator(api).pages(cookie, account, definition, arguments, spec)
    }
}

private fun JSONObject.jsonList(): List<JSONObject> {
    val rows = optJSONArray("list") ?: optJSONArray("avatar_list") ?: JSONArray()
    return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
}
