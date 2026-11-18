package com.atuy.hoyodashboard.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject

class UpstreamFeatureClient(
    private val api: UpstreamApiClient,
    private val cookieProvider: () -> String,
    val account: ApiAccount
) {
    constructor(api: UpstreamApiClient, cookie: String, account: ApiAccount) :
        this(api, { cookie }, account)

    fun availableMethods(): List<ApiMethod> = api.available(account)

    suspend fun raw(
        method: String,
        arguments: Map<String, String> = emptyMap()
    ): JSONObject = api.call(cookieProvider(), account, method, arguments)

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
        val response = raw(method)
        val characters = response.jsonList()
        return if (account.game == ApiGame.GENSHIN) {
            characters.map { character ->
                decorateGenshinCharacter(character, response.optJSONObject("property_map"))
            }
        } else characters
    }

    suspend fun fullBattleRecord(): JSONObject = when (account.game) {
        ApiGame.GENSHIN -> JSONObject()
            .put("user", raw("get_genshin_user"))
            .put("spiral_abyss", raw("get_genshin_spiral_abyss"))
            .put("previous_spiral_abyss", optionalRaw("get_genshin_spiral_abyss_previous"))
            .put("imaginarium_theater", optionalRaw("get_imaginarium_theater"))
            .put("activities", optionalRaw("get_genshin_activities"))
            .put("envisaged_echoes", optionalRaw("get_envisaged_echoes"))
            .put("stygian_onslaught", optionalRaw("get_stygian_onslaught"))
            .put("tcg_preview", optionalRaw("get_genshin_tcg_preview"))
            .put("tcg_matches", optionalRaw("get_genshin_tcg_matches"))
        ApiGame.STARRAIL -> JSONObject()
            .put("user", raw("get_starrail_user"))
            .put("forgotten_hall", raw("get_starrail_challenge"))
            .put("simulated_universe", raw("get_starrail_rogue"))
            .put("pure_fiction", raw("get_starrail_pure_fiction"))
            .put("apocalyptic_shadow", raw("get_starrail_apc_shadow"))
            .put("anomaly_arbitration", optionalRaw("get_anomaly_arbitration"))
        ApiGame.ZZZ -> JSONObject()
            .put("user", raw("get_zzz_user"))
            .put("shiyu_defense", raw("get_shiyu_defense"))
            .put("deadly_assault", raw("get_deadly_assault"))
            .put("annihilation_simulacrum", raw("get_annihilation_simulacrum"))
            .put("lost_void", raw("get_lost_void_summary"))
            .put("threshold_simulation_brief", optionalRaw("get_threshold_simulation_brief"))
            .put("threshold_simulation", optionalRaw("get_threshold_simulation"))
        ApiGame.HONKAI -> raw("get_full_honkai_user")
        ApiGame.TOT -> throw UnsupportedOperationException("No Battle Chronicle exists for Tears of Themis.")
    }

    private suspend fun optionalRaw(method: String): JSONObject = try {
        raw(method)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        JSONObject().put("_error", error.message ?: "取得できませんでした")
    }

    suspend fun gachaHistory(
        limit: Int? = null,
        perBannerLimit: Int? = null,
        authKey: String? = null,
        miliastra: Boolean = false
    ): List<GachaRecord> {
        val method = when (account.game) {
            ApiGame.GENSHIN -> if (miliastra) "mw_wish_history" else "wish_history"
            ApiGame.STARRAIL -> "warp_history"
            ApiGame.ZZZ -> if (authKey.isNullOrBlank()) "chronicle_signal_history" else "signal_history"
            else -> throw UnsupportedOperationException("Gacha history is not available for " + account.game)
        }
        val args = buildMap {
            limit?.takeIf { it > 0 }?.let { put("limit", it.toString()) }
            perBannerLimit?.takeIf { it > 0 }?.let { put("per_banner_limit", it.toString()) }
            authKey?.takeIf(String::isNotBlank)?.let { put("authkey", it) }
        }
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
        val currency = if (account.game == ApiGame.ZZZ) {
            require(type in 1..3) { "通貨を選択してください" }
            listOf("PolychromesData", "MatserTapeData", "BooponsData")[type - 1]
        } else type.toString()
        val arguments = mapOf("month" to monthValue, "type" to currency)
        if (account.game != ApiGame.ZZZ) return raw(method, arguments).jsonList()
        return UpstreamPaginator(api).items(
            cookieProvider(), account, checkNotNull(UpstreamCatalog.find(method)), arguments,
            PageSpec(cursorParameter = "current_page", pageSize = 20)
        ).toList()
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
        return UpstreamPaginator(api).pages(cookieProvider(), account, definition, arguments, spec)
    }
}

private fun JSONObject.jsonList(): List<JSONObject> {
    val rows = optJSONArray("list") ?: optJSONArray("avatar_list") ?: optJSONArray("avatars")
        ?: optJSONArray("characters") ?: JSONArray()
    return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
}

internal fun decorateGenshinCharacter(row: JSONObject, propertyMap: JSONObject?): JSONObject {
    fun decorate(element: Any?): Any? = when (element) {
        is JSONObject -> JSONObject().apply {
            element.keys().forEach { key -> put(key, decorate(element.opt(key))) }
            if (has("property_type")) {
                val info = propertyMap?.optJSONObject(optString("property_type"))
                val name = info?.optString("name").orEmpty()
                if (name.isNotBlank()) {
                    put("name", name)
                    remove("property_type")
                }
                info?.optString("icon")?.takeIf { it.isNotBlank() }?.let { put("icon", it) }
            }
        }
        is JSONArray -> JSONArray().apply {
            for (index in 0 until element.length()) put(decorate(element.opt(index)))
        }
        else -> element
    }
    val output = decorate(row) as JSONObject
    val base = output.optJSONObject("base") ?: return output
    base.keys().forEach { key ->
        if (!output.has(key)) output.put(key, base.opt(key))
    }
    output.remove("base")
    return output
}
