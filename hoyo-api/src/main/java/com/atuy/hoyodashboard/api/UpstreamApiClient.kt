package com.atuy.hoyodashboard.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class ApiAccount(
    val game: ApiGame,
    val uid: String,
    val server: String,
    val region: ApiRegion = ApiRegion.OVERSEAS
) {
    companion object {
        fun from(role: Role) = ApiAccount(ApiGame.from(role.game), role.uid, role.server, role.region)
    }
}

class UpstreamApiClient(
    private val language: String = "ja-jp",
    private val onCookieUpdate: ((String) -> Unit)? = null,
    private val transport: ApiTransport = ApiTransport(onCookieUpdate),
    private val badges: BadgeSession = BadgeSession(transport),
    private val sessions: CookieSessionManager = CookieSessionManager(onCookieUpdate, transport)
) {
    private val authKeyMutex = Mutex()
    private val authKeyCache = mutableMapOf<String, Pair<Long, String>>()

    fun available(game: ApiGame): List<ApiMethod> =
        UpstreamCatalog.methods.filter { it.game == null || it.game == game }

    fun available(account: ApiAccount): List<ApiMethod> {
        val globalOnlyNames = setOf(
            "get_web_events",
            "get_accompany_characters",
            "get_accompany_character_details",
            "get_accompany_character_posts",
            "get_accompany_voice_setting"
        )
        return available(account.game).filterNot { method ->
            if (method.name == "get_banner_details" && account.game !in setOf(ApiGame.GENSHIN, ApiGame.STARRAIL)) {
                return@filterNot true
            }
            account.region == ApiRegion.CHINESE && (
                method.scope in setOf(
                    ApiScope.MIMO, ApiScope.TEAPOT
                ) ||
                    method.name in globalOnlyNames
                )
        }
    }

    suspend fun call(
        cookie: String,
        account: ApiAccount,
        name: String,
        arguments: Map<String, String> = emptyMap()
    ): JSONObject {
        when (name) {
            "get_recommended_users" -> return JSONObject().put("list", JSONArray())
            "get_gacha_items", "get_genshin_gacha_items" ->
                throw UnsupportedOperationException("参照元でも祈願アイテムの一覧取得は利用できません")
        }
        val target = upstreamAliases[name] ?: name
        val method = UpstreamCatalog.find(target)
            ?: throw IllegalArgumentException("Unknown upstream method: " + name)
        val result = call(cookie, account, method, arguments)
        return if (name == "get_record_card") result.optJSONArray("list")?.optJSONObject(0) ?: JSONObject()
        else result
    }

    suspend fun call(
        cookie: String,
        account: ApiAccount,
        method: ApiMethod,
        arguments: Map<String, String> = emptyMap()
    ): JSONObject {
        return request(cookie, account, method, upstreamOptions(method.name, arguments), aggregate = true)
    }

    internal suspend fun callPage(
        cookie: String,
        account: ApiAccount,
        method: ApiMethod,
        arguments: Map<String, String>
    ): JSONObject = request(cookie, account, method, upstreamOptions(method.name, arguments), aggregate = false)

    private suspend fun request(
        cookie: String,
        originalAccount: ApiAccount,
        definition: ApiMethod,
        options: Map<String, String>,
        aggregate: Boolean
    ): JSONObject {
        val method = if (definition.name == "get_calculator_characters" && options["sync"] == "true") {
            checkNotNull(UpstreamCatalog.find("get_owned_calculator_characters"))
        } else definition
        val selectors = method.scope in uidScoped || method.name == "get_lineups" || method.name == "teapot_replicas"
        val inputUid = options["uid"]
        val explicitServer = options["server"]
        val account = if (selectors && inputUid != null) originalAccount.withUid(inputUid, explicitServer)
            else originalAccount
        val requestLanguage = options["lang"] ?: language
        require(requestLanguage.matches(Regex("[a-zA-Z]{2,3}(?:-[a-zA-Z]{2,4})?"))) { "言語コードが無効です" }
        val autoAuth = options["autoauth"] == "true"
        val rawResponse = options["raw"] == "true" || options["return_raw_data"] == "true"
        val arguments = options - setOf("sync", "autoauth", "raw", "return_raw_data", "use_cache") -
            if (selectors) setOf("uid", "server") else emptySet()
        require(method.readOnly) { "Write operations require a separate explicit API." }
        if (account.region == ApiRegion.CHINESE && (
            method.scope == ApiScope.MIMO ||
                method.name in setOf(
                    "get_web_events",
                    "get_accompany_characters",
                    "get_accompany_character_details",
                    "get_accompany_character_posts",
                    "get_accompany_voice_setting"
                )
            )
        ) {
            throw UnsupportedOperationException(
                method.name + " is only available for overseas HoYoLAB accounts."
            )
        }
        require(method.game == null || method.game == account.game) {
            "Endpoint not supported for " + account.game
        }
        if (method.scope == ApiScope.ANNOUNCEMENT) {
            return announcements(account, arguments)
        }
        if (method.name == "get_starrail_lineup_floor") {
            val type = arguments["type"] ?: error("ゲームモードを選択してください")
            val floor = arguments["floor"]?.toIntOrNull() ?: error("階層を入力してください")
            val modes = call(cookie, account, "get_starrail_lineup_game_modes", arguments.filterKeys { it == "lang" })
            val mode = modes.optJSONArray("tree").objects().firstOrNull { it.optString("root_type") == type }
            return mode?.optJSONArray("children")?.optJSONObject(0)?.optJSONArray("children").objects()
                .firstOrNull { it.optJSONObject("extend")?.optInt("floor") == floor } ?: JSONObject()
        }
        if (method.name == "get_genshin_banner_ids") {
            val response = transport.send(
                route(ApiScope.WEBSTATIC, account.game, ApiRegion.CHINESE),
                method.endpoint,
                "",
                ApiRegion.CHINESE,
                language,
                "GET",
                emptyMap(),
                null
            )
            val rows = response.optJSONArray("list")
                ?: response.optJSONObject("data")?.optJSONArray("list")
                ?: JSONArray()
            val ids = JSONArray()
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val id = if (item.optInt("gacha_type") == 500) {
                    "8b10b48c52dd6870f92d72e9963b44bb8968ed2f"
                } else item.optString("gacha_id")
                if (id.isNotBlank()) ids.put(id)
            }
            return JSONObject().put("list", ids)
        }
        if (method.name == "get_banner_details") {
            require(account.game == ApiGame.GENSHIN || account.game == ApiGame.STARRAIL)
            val ids = arguments["banner_ids"]?.let { value ->
                runCatching { JSONArray(value) }.getOrElse {
                    JSONArray(value.split(',').map(String::trim).filter(String::isNotBlank))
                }
            } ?: if (account.game == ApiGame.GENSHIN) {
                val response = call(cookie, account, "get_genshin_banner_ids")
                response.optJSONArray("list") ?: JSONArray()
            } else {
                throw IllegalArgumentException("Star Rail banner_ids must be supplied.")
            }
            val gameName = if (account.game == ApiGame.STARRAIL) "hkrpg" else "hk4e"
            val serverName = if (account.game == ApiGame.STARRAIL) "prod_official_asia" else "os_asia"
            val lang = arguments["lang"] ?: language
            val details = JSONArray()
            for (i in 0 until ids.length()) {
                val bannerId = ids.opt(i)?.toString().orEmpty()
                require(bannerId.matches(Regex("[a-zA-Z0-9_-]+")) && lang.matches(Regex("[a-zA-Z-]+"))) {
                    "バナーIDまたは言語が無効です"
                }
                val path = "gacha_info/$gameName/$serverName/$bannerId/$lang.json"
                val item = transport.send(
                    route(ApiScope.WEBSTATIC, account.game, account.region),
                    path, "", account.region, language, "GET", emptyMap(), null
                )
                details.put(JSONObject(item.toString()).put("banner_id", bannerId))
            }
            return JSONObject().put("list", details)
        }
        if (method.name == "get_genshin_banner_names") {
            val rawMethod = method.copy(name = "get_genshin_banner_names_raw")
            val response = call(cookie, account, rawMethod, arguments)
            val rows = response.optJSONArray("gacha_type_list") ?: JSONArray()
            val names = JSONObject()
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                names.put(item.optString("key"), item.optString("name"))
            }
            return names
        }
        if (aggregate && method.name in setOf(
            "wish_history", "mw_wish_history", "warp_history", "signal_history", "chronicle_signal_history"
        )
        ) {
            return allGachaHistory(cookie, account, method, arguments)
        }
        if (aggregate && method.name == "transaction_log") {
            return allTransactionHistory(cookie, account, method, arguments)
        }
        val pageSizes = mapOf(
            "claimed_rewards" to 10,
            "genshin_diary_log" to 100,
            "starrail_diary_log" to 100,
            "get_lineups" to 20,
            "get_tcg_decks" to 20,
            "genshin_tcg" to 32,
            "teapot_replicas" to 20,
            "get_calculator_characters" to 200,
            "get_owned_calculator_characters" to 200,
            "get_calculator_weapons" to 200,
            "get_calculator_artifacts" to 200,
            "get_calculator_furnishings" to 200
        )
        if (aggregate) pageSizes[method.name]?.let { defaultSize ->
            val pageSize = arguments["page_size"]?.toIntOrNull() ?: defaultSize
            require(pageSize > 0) { "ページあたりの件数は1以上を指定してください" }
            val pageArguments = if (autoAuth) arguments + ("autoauth" to "true") else arguments
            return collectPages(cookie, account, method, pageArguments, pageSize)
        }
        if (method.name == "get_full_genshin_user") return coroutineScope {
            val user = async { call(cookie, account, "get_genshin_user", arguments) }
            val current = async { call(cookie, account, "get_genshin_spiral_abyss", arguments) }
            val previous = async { call(cookie, account, "get_genshin_spiral_abyss_previous", arguments) }
            val activities = async { call(cookie, account, "get_genshin_activities", arguments) }
            JSONObject(user.await().toString()).put("abyss", JSONObject()
                .put("current", current.await()).put("previous", previous.await()))
                .put("activities", activities.await())
        }
        if (method.name == "get_honkai_abyss") {
            for (name in listOf("get_honkai_old_abyss", "get_honkai_superstring_abyss")) {
                try {
                    return call(cookie, account, name, arguments)
                } catch (error: HoYoApiException) {
                    if (error.code !in setOf(-1, 10307)) throw error
                }
            }
            return JSONObject().put("list", JSONArray())
        }
        if (method.name == "get_full_honkai_user") return coroutineScope {
            val user = async { call(cookie, account, "get_honkai_user", arguments) }
            val suits = async { call(cookie, account, "get_honkai_battlesuits", arguments) }
            val abyss = async { call(cookie, account, "get_honkai_abyss", arguments) }
            val arena = async { call(cookie, account, "get_honkai_memorial_arena", arguments) }
            val realm = async { call(cookie, account, "get_honkai_elysian_realm", arguments) }
            JSONObject(user.await().toString())
                .put("battlesuits", suits.await().optJSONArray("list"))
                .put("abyss", abyss.await().optJSONArray("list"))
                .put("memorial_arena", arena.await().optJSONArray("list"))
                .put("elysian_realm", realm.await().optJSONArray("list"))
        }
        if (method.name == "get_starrail_five_star_warps" && aggregate) {
            return fiveStarWarps(cookie, account, method, arguments)
        }
        if (method.name == "get_tcg_cards") {
            val characterMethod = method.copy(name = "tcg_character_cards", endpoint = "roles", verb = "GET")
            val wanted = arguments["card_types"]?.let(::intList)?.toSet().orEmpty()
            val lang = arguments.filterKeys { it == "lang" }
            val characterData = call(cookie, account, characterMethod, lang)
            val characterRows = characterData.optJSONArray("roles").objects()
            val cards = if (wanted.isEmpty() || 0 in wanted) JSONArray(characterRows) else JSONArray()
            val ids = JSONArray(characterRows.mapNotNull { it.optInt("id").takeIf { id -> id > 0 } })
            if (wanted.isEmpty() || wanted.any { it != 0 }) {
                val actionMethod = method.copy(
                    name = "tcg_action_cards", endpoint = "actions",
                    verb = "READ_POST", required = setOf("role_ids")
                )
                val actionData = call(cookie, account, actionMethod, lang + ("role_ids" to ids.toString()))
                actionData.optJSONArray("actions").objects()
                    .filter { wanted.isEmpty() || it.optInt("card_type") in wanted }
                    .forEach { cards.put(it) }
            }
            return JSONObject().put("list", cards)
        }
        if (aggregate && method.name == "get_tcg_deck_comments") {
            return allTcgDeckComments(cookie, account, method, arguments)
        }
        if (method.name == "get_all_zzz_agent_upgrade_guides") {
            val agents = call(cookie, account, "get_zzz_upgrade_guide_agents", arguments.filterKeys { it == "lang" })
            val rows = agents.optJSONArray("list") ?: JSONArray()
            val results = JSONArray()
            val unlockedOnly = arguments["unlocked_only"]?.toBooleanStrictOrNull() ?: true
            val ids = rows.objects().filter { !unlockedOnly || it.optBoolean("unlocked") }
                .mapNotNull { row -> (row.optJSONObject("avatar") ?: row).optInt("id").takeIf { it > 0 } }
            for (batch in ids.chunked(10)) {
                val guides = call(cookie, account, "get_zzz_agent_upgrade_guide",
                    arguments.filterKeys { it == "lang" } + ("avatar_list" to agentGuideSelection(batch)))
                val list = guides.optJSONArray("list") ?: JSONArray()
                for (i in 0 until list.length()) results.put(list.opt(i))
            }
            return JSONObject().put("list", results)
        }
        if (method.name == "get_genshin_user" || method.name == "get_starrail_user") {
            val index = call(cookie, account, method.copy(name = method.name + "_index"), arguments)
            val other = if (method.name == "get_genshin_user") {
                "get_genshin_characters"
            } else "get_starrail_basic_info"
            val details = call(cookie, account, other, arguments)
            val combined = JSONObject(index.toString())
            if (method.name == "get_starrail_user") return combined.put("info", details)
            val keys = details.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                combined.put(key, details.opt(key))
            }
            return combined
        }
        if (method.name == "get_genshin_detailed_characters" &&
            arguments["character_ids"].isNullOrBlank()
        ) {
            val characters = call(cookie, account, "get_genshin_characters", arguments.filterKeys { it == "lang" })
            val rows = characters.optJSONArray("list") ?: JSONArray()
            val ids = JSONArray()
            for (i in 0 until rows.length()) {
                val id = rows.optJSONObject(i)?.optInt("id", 0) ?: 0
                if (id > 0) ids.put(id)
            }
            if (ids.length() == 0) return JSONObject().put("list", JSONArray())
            return call(
                cookie, account, method.copy(name = "get_genshin_character_details_raw"),
                arguments + ("character_ids" to ids.toString())
            )
        }
        if (method.name == "get_zzz_agent_info" && arguments["id_list[]"].isNullOrBlank()) {
            val locale = arguments.filterKeys { it == "lang" }
            val requested = arguments["character_id"]?.let(::intList)
            val ids = requested ?: call(cookie, account, "get_zzz_agents", locale).let { agents ->
                (agents.optJSONArray("avatar_list") ?: agents.optJSONArray("list")).objects()
                    .mapNotNull { it.optInt("id").takeIf { id -> id > 0 } }
            }
            val details = JSONArray()
            for (id in ids) {
                val result = call(cookie, account, method.copy(name = "get_zzz_agent_info_raw"),
                    locale + ("id_list[]" to id.toString()))
                (result.optJSONArray("avatar_list")?.optJSONObject(0) ?: result).let { details.put(it) }
            }
            return JSONObject().put("avatar_list", details)
        }
        if (method.name == "get_threshold_simulation" && arguments["void_front_id"].isNullOrBlank()) {
            val response = call(cookie, account, "get_threshold_simulation_brief", arguments)
            val brief = response.optJSONObject("void_front_battle_abstract_info_brief") ?: response
            val id = brief.optString("id").ifBlank { brief.optString("void_front_id") }
            if (id.isBlank()) return response
            return call(
                cookie, account, method.copy(name = "get_threshold_simulation_raw"),
                arguments + ("void_front_id" to id)
            )
        }
        if (method.name == "get_mimo_point_count") {
            val game = mimoGame(cookie, account, arguments["game_id"]?.toIntOrNull(), requestLanguage)
            return JSONObject().put("point", game.optInt("point"))
        }
        val params = method.defaults.mapValues { (_, value) ->
            value.replace("{uid}", account.uid).replace("{server}", account.server)
        }.toMutableMap()
        params.putAll(arguments - "lang")
        if (method.name == "get_starrail_characters") params.remove("simple")
        if (method.name == "get_calculator_furnishings") {
            params.putIfAbsent("cat_id", "0")
            params.putIfAbsent("weapon_levels", "0")
        }
        if (method.name == "get_tcg_card_effect") {
            val kind = params.remove("card_type")
            if (kind != null) params["__tcg_action_effect"] = (kind != "0").toString()
        }
        if (method.name == "genshin_tcg") {
            val page = params.remove("page")?.toIntOrNull() ?: 1
            val size = params.remove("page_size")?.toIntOrNull() ?: 32
            params["offset"] = ((page - 1) * size).toString()
            params["limit"] = size.toString()
            params["need_avatar"] = (params.remove("characters")?.toBooleanStrictOrNull() ?: true).toString()
            params["need_action"] = (params.remove("action")?.toBooleanStrictOrNull() ?: true).toString()
        }
        if (method.name == "get_record_cards" && params["uid"].isNullOrBlank()) {
            val cookieValues = cookie.split(';').mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }.toMap()
            params["uid"] = cookieValues["ltuid_v2"] ?: cookieValues["ltuid"]
                ?: cookieValues["account_id_v2"] ?: cookieValues["account_id"]
                ?: throw IllegalArgumentException("HoYoLAB account ID is missing from cookies.")
        }
        if (method.scope == ApiScope.MIMO && method.required.any { it == "game_id" }) {
            if (params["game_id"].isNullOrBlank() || params["version_id"].isNullOrBlank()) {
                val game = mimoGame(cookie, account, params["game_id"]?.toIntOrNull(), requestLanguage)
                params["game_id"] = game.getString("game_id")
                params["version_id"] = game.getString("version_id")
            }
        }
        if (method.endpoint == "gacha_record") {
            val type = params["gacha_type"]
            val map = mapOf(
                "1" to "GACHA_TYPE_PERMANENT", "2" to "GACHA_TYPE_CHARACTER_UP", "3" to "GACHA_TYPE_WEAPON_UP",
                "5" to "GACHA_TYPE_BANGBOO", "102" to "GACHA_TYPE_CHARACTER_RETURN", "103" to "GACHA_TYPE_WEAPON_RETURN"
            )
            map[type]?.let { params["gacha_type"] = it }
        }
        if (method.name == "teapot_replicas") {
            val page = params.remove("page")?.toIntOrNull() ?: 1
            val size = params.remove("page_size")?.toIntOrNull() ?: 20
            params["next"] = (page - 1).coerceAtLeast(0).times(size).toString()
            params["limit"] = size.toString()
            arguments["region"]?.let { params["target_region"] = it }
            if (inputUid != null && params["target_region"].isNullOrBlank()) params["target_region"] = account.server
            params.remove("region")
        }
        val missing = method.required.filter { key ->
            params[key].isNullOrBlank() || params[key]!!.startsWith("{")
        }
        require(missing.isEmpty()) { "入力されていない項目があります" }
        val transactionKind = if (method.scope == ApiScope.TRANSACTION) {
            params.remove("kind")?.lowercase()?.also {
                require(it in setOf("primogem", "crystal", "resin", "artifact", "weapon")) {
                    "Unsupported transaction kind: " + it
                }
            }
        } else null
        if (method.authKeyRequired && params["authkey"].isNullOrBlank()) {
            val authAppId = if (method.scope == ApiScope.TRANSACTION) "csc" else "webview_gacha"
            params["authkey"] = generateAuthKey(cookie, account, authAppId)
        }
        val requestRegion = account.region
        val base = route(method.scope, account.game, requestRegion)
        if (method.scope == ApiScope.RECORD) {
            if (method.useUidParam) {
                params["uid"] = account.uid
                params["region"] = account.server
            } else {
                params["role_id"] = account.uid
                params["server"] = account.server
            }
        }
        if (method.scope == ApiScope.ZZZ_LEDGER || (method.scope == ApiScope.CULTIVATE && method.verb == "GET")) {
            params["uid"] = account.uid
            params["region"] = account.server
            if (method.scope == ApiScope.ZZZ_LEDGER) params.putIfAbsent("month", "")
        }
        if (method.name == "get_starrail_lineups") {
            params["lineup_type"] = params.remove("type").orEmpty()
            require(params["lineup_type"] in setOf("Chasm", "Story", "Boss", "Peak")) {
                "編成モードが無効です"
            }
            params["game"] = "hkrpg"
            params.putIfAbsent("uid", account.uid)
            params.putIfAbsent("region", account.server)
        }
        if (method.name == "get_tcg_card_effect") {
            val cardId = params["id"]?.toIntOrNull() ?: throw IllegalArgumentException("カード番号が無効です")
            params.putIfAbsent("__tcg_action_effect", (cardId >= 10000).toString())
        }
        val repeated = if (method.name == "get_lineups" && !params["roles"].isNullOrBlank()) {
            mapOf("roles" to intList(checkNotNull(params.remove("roles"))).map(Int::toString))
        } else emptyMap()
        if (method.name == "get_lineups") {
            if (params["order"] == "Match") {
                params["uid"] = account.uid
                params["region"] = account.server
            }
        }
        if (method.name == "get_web_events") {
            val gameId = when (account.game) {
                ApiGame.HONKAI -> 1
                ApiGame.GENSHIN -> 2
                ApiGame.TOT -> 4
                ApiGame.STARRAIL -> 6
                ApiGame.ZZZ -> 8
            }
            params.putIfAbsent("gids", gameId.toString())
            params.putIfAbsent("size", "15")
            params.putIfAbsent("offset", "")
        }
        if (method.scope == ApiScope.REWARD) {
            if (account.region == ApiRegion.CHINESE) {
                params["uid"] = account.uid
                params["region"] = account.server
            } else {
                params.putIfAbsent("lang", requestLanguage)
            }
        }
        if (method.scope == ApiScope.LEDGER) {
            if (account.region == ApiRegion.CHINESE && account.game == ApiGame.GENSHIN) {
                params["bind_uid"] = account.uid
                params["bind_region"] = account.server
            } else {
                params["uid"] = account.uid
                params["region"] = account.server
            }
            val now = YearMonth.now(ZoneOffset.ofHours(8))
            params.putIfAbsent("month", if (account.game == ApiGame.STARRAIL) {
                now.format(DateTimeFormatter.ofPattern("yyyyMM"))
            } else now.monthValue.toString())
            params["lang"] = requestLanguage
        }
        if (method.scope in setOf(ApiScope.LINEUP, ApiScope.CARD_PLAZA, ApiScope.TEAPOT, ApiScope.MIMO)) {
            params.putIfAbsent("lang", requestLanguage)
        }
        if (method.scope == ApiScope.HSR_WARP) {
            params["uid"] = account.uid
            params["region"] = account.server
            params["game_biz"] = if (account.region == ApiRegion.CHINESE) "hkrpg_cn" else "hkrpg_global"
            params["gacha_type"]?.let { type ->
                params["gacha_type"] = starRailWarpTypes[type] ?: type.also {
                    require(it in starRailWarpTypes.values) { "跳躍の種類が無効です" }
                }
            }
        }
        if (method.scope == ApiScope.GACHA) {
            params["authkey_ver"] = "1"
            params["authkey"] = decodeHistoryAuthKey(params["authkey"].orEmpty())
            val lang = requestLanguage
            params["lang"] = if (method.endpoint == "getBeyondGachaLog" || lang.startsWith("zh-")) lang else lang.substringBefore('-')
            params["game_biz"] = gameBiz(account)
            if (method.endpoint in setOf("getGachaLog", "getBeyondGachaLog", "getLdGachaLog")) {
                params.putIfAbsent("size", "20")
                params.putIfAbsent("end_id", "0")
                params["real_gacha_type"] = params["gacha_type"] ?: error("gacha_type is required")
            }
        }
        if (method.scope == ApiScope.TRANSACTION) {
            params["authkey_ver"] = "1"
            params["sign_type"] = "2"
            params["authkey"] = decodeHistoryAuthKey(params["authkey"].orEmpty())
            val lang = requestLanguage
            params["lang"] = if (lang.startsWith("zh-")) lang else lang.substringBefore('-')
            params.putIfAbsent("size", "20")
            params.putIfAbsent("end_id", "0")
        }
        if (method.scope == ApiScope.CALCULATOR) {
            params.putIfAbsent("lang", requestLanguage)
            if (method.name == "get_character_details") params.putIfAbsent("uid", account.uid)
            if (method.name in setOf("get_character_details", "get_teapot_replica_blueprint")) {
                params.putIfAbsent("region", account.server)
            }
        }
        val lineupSchedule = if (method.name == "get_starrail_lineup_schedules") {
            when (params.remove("type") ?: "Chasm") {
                "Chasm" -> "schedule/list"
                "Story" -> "story_schedule/list"
                "Boss" -> "boss_schedule/list"
                else -> throw IllegalArgumentException("開催期間に対応していないモードです")
            }
        } else null
        val path = when {
            lineupSchedule != null -> lineupSchedule
            method.name == "get_hoyolab_user" && account.region == ApiRegion.CHINESE ->
                "user/wapi/getUserFullInfo"
            method.name == "get_tcg_card_effect" && params.remove("__tcg_action_effect") == "true" ->
                "action/skill"
            transactionKind != null -> "Get" + transactionKind.replaceFirstChar { it.uppercase() } + "Log"
            method.scope == ApiScope.MIMO &&
                (account.game == ApiGame.GENSHIN || params["game_id"] == "2") ->
                "qiuqiu/" + method.endpoint.replace("-", "_")
            method.scope == ApiScope.LEDGER && account.region == ApiRegion.CHINESE &&
                account.game == ApiGame.GENSHIN && method.endpoint == "month_info" -> "monthInfo"
            method.scope == ApiScope.LEDGER && account.region == ApiRegion.CHINESE &&
                account.game == ApiGame.GENSHIN && method.endpoint == "month_detail" -> "monthDetail"
            else -> method.endpoint
        }

        val post = method.verb == "READ_POST"
        val query = when {
            !post -> params
            method.scope == ApiScope.CULTIVATE -> mapOf("uid" to account.uid, "region" to account.server)
            method.scope == ApiScope.CARD_PLAZA -> mapOf("lang" to checkNotNull(params.remove("lang")))
            else -> emptyMap()
        }
        val body = if (post) jsonBody(params).apply {
            if (method.name == "get_calculator_furnishings") optString("weapon_levels").toIntOrNull()?.let {
                put("weapon_levels", it)
            }
            if (method.scope == ApiScope.CALCULATOR && has("uid")) {
                optString("uid").toLongOrNull()?.let { put("uid", it) }
            }
        } else null
        val headers = when (method.scope) {
            ApiScope.REWARD -> {
                val signGame = when (account.game) {
                    ApiGame.GENSHIN -> "hk4e"
                    ApiGame.STARRAIL -> "hkrpg"
                    ApiGame.ZZZ -> "zzz"
                    ApiGame.HONKAI -> "bh3"
                    ApiGame.TOT -> "nxx"
                }
                if (account.region == ApiRegion.OVERSEAS) {
                    mapOf(
                        "x-rpc-signgame" to signGame,
                        "Referer" to "https://act.hoyolab.com/"
                    )
                } else {
                    mapOf(
                        "x-rpc-signgame" to signGame,
                        "DS" to simpleDynamicSecret("LyD1rXqMv2GJhnwdvCBjFOKGiKuLY3aO"),
                        "x-rpc-app_version" to "2.70.1",
                        "x-rpc-client_type" to "5",
                        "x-rpc-device_id" to java.util.UUID.randomUUID().toString(),
                        "x-rpc-sys_version" to "12",
                        "x-rpc-platform" to "android",
                        "x-rpc-channel" to "miyousheluodi",
                        "x-rpc-device_model" to cookieValue(cookie, "ltuid_v2", "ltuid", "account_id").orEmpty()
                    )
                }
            }
            ApiScope.CALCULATOR -> mapOf(
                "Referer" to if (account.region == ApiRegion.OVERSEAS) {
                    "https://act.hoyolab.com/"
                } else "https://webstatic.mihoyo.com/ys/event/e20200923adopt_calculator/index.html"
            )
            ApiScope.BBS -> mapOf(
                "Referer" to if (account.region == ApiRegion.CHINESE) {
                    "https://bbs.mihoyo.com/"
                } else "https://www.hoyolab.com/"
            )
            else -> emptyMap()
        }
        val workingCookie = if (method.name == "get_accompany_character_details") withStuid(cookie) else cookie
        val activeCookie = if (method.scope in setOf(ApiScope.WEBSTATIC, ApiScope.GACHA, ApiScope.TRANSACTION)) {
            ""
        } else if (method.requiresBadge) {
            try {
                badges.cookieFor(cookie, account, requestLanguage)
            } catch (error: HoYoApiException) {
                if (!isRefreshable(error)) throw error
                val refreshed = sessions.refresh(cookie, account.region) ?: throw error
                badges.invalidate(account)
                badges.cookieFor(refreshed, account, requestLanguage)
            }
        } else workingCookie
        val rpcHeaders = method.scope in setOf(
            ApiScope.RECORD, ApiScope.CARD, ApiScope.BBS, ApiScope.ANNOUNCEMENT,
            ApiScope.CULTIVATE, ApiScope.HSR_WARP, ApiScope.HSR_LINEUP, ApiScope.ZZZ_LEDGER, ApiScope.WIKI, ApiScope.ACCOUNTS
        )
        val signed = method.scope in setOf(
            ApiScope.RECORD, ApiScope.CARD, ApiScope.BBS, ApiScope.ANNOUNCEMENT,
            ApiScope.HSR_LINEUP, ApiScope.ZZZ_LEDGER, ApiScope.ACCOUNTS
        ) || ((method.scope == ApiScope.CULTIVATE || method.scope == ApiScope.HSR_WARP) &&
            account.region == ApiRegion.CHINESE)
        suspend fun send(requestCookie: String): JSONObject = transport.send(
            base, path, requestCookie, requestRegion, requestLanguage,
            if (post) "POST" else "GET", query, body, headers,
            rpcHeaders = rpcHeaders, signed = signed, repeated = repeated
        )
        val response = try {
            send(activeCookie)
        } catch (error: HoYoApiException) {
            if (autoAuth && originalAccount == account && method.name in realTimeNotes && error.code == 10102) {
                UpstreamActions(requestLanguage, transport, this).updateSettings(activeCookie, account, 3, true)
                return send(activeCookie)
            }
            if (autoAuth && method.name == "get_owned_calculator_characters" && error.code == -502002) {
                UpstreamActions(requestLanguage, transport, this).setCalculatorSync(activeCookie, account, true)
                return send(activeCookie)
            }
            if (activeCookie.isBlank() || !isRefreshable(error)) throw error
            if (method.requiresBadge && error.code == -100) {
                badges.invalidate(account)
                val renewed = try {
                    badges.cookieFor(cookie, account, requestLanguage)
                } catch (badgeError: HoYoApiException) {
                    if (!isRefreshable(badgeError)) throw badgeError
                    val refreshed = sessions.refresh(cookie, account.region) ?: throw badgeError
                    badges.cookieFor(refreshed, account, requestLanguage)
                }
                send(renewed)
            } else {
                val refreshedBase = sessions.refresh(cookie, account.region) ?: throw error
                val refreshed = if (method.requiresBadge) badges.cookieFor(refreshedBase, account, requestLanguage)
                    else refreshedBase
                send(refreshed)
            }
        }
        return if (aggregate) normalizeUpstreamResponse(method.name, response, account, rawResponse,
            simple = arguments["simple"] == "true") else response
    }

    internal suspend fun mimoGame(
        cookie: String, account: ApiAccount, gameId: Int? = null, locale: String = language
    ): JSONObject {
        val id = gameId ?: when (account.game) {
            ApiGame.GENSHIN -> 2
            ApiGame.STARRAIL -> 6
            ApiGame.ZZZ -> 8
            else -> 5
        }
        val data = call(cookie, account, "get_mimo_games", mapOf("lang" to locale))
        val rows = data.optJSONArray("list") ?: data.optJSONArray("act_list")
        return rows.objects().map { it.optJSONObject("act_info") ?: it }.firstOrNull { it.optInt("game_id") == id }
            ?: throw HoYoApiException("このアカウントにMimoの開催情報がありません")
    }

    suspend fun fetchAuthKey(cookie: String, account: ApiAccount, purpose: String = "webview_gacha"): String {
        require(purpose in setOf("webview_gacha", "csc")) { "履歴の種類が無効です" }
        return generateAuthKey(cookie, account, purpose)
    }

    suspend fun fetchMi18n(base: String, filename: String, locale: String = language): Map<String, String> {
        require(filename.matches(Regex("[a-zA-Z0-9_.-]+")) && locale.matches(Regex("[a-zA-Z-]+"))) {
            "ファイル名または言語が無効です"
        }
        val result = transport.send(base, "$filename/$filename-$locale.json", "", ApiRegion.OVERSEAS,
            locale, "GET", emptyMap(), null)
        return result.keys().asSequence().mapNotNull { key -> (result.opt(key) as? String)?.let { key to it } }.toMap()
    }

    private fun gachaTimezoneOffset(account: ApiAccount, page: JSONObject, chronicle: Boolean): Int {
        if (chronicle) return account.offsetHours - 8
        return when (account.game) {
            ApiGame.STARRAIL -> page.optInt("region_time_zone", account.offsetHours) - 8
            ApiGame.ZZZ -> page.optInt("region_time_zone", account.offsetHours - 8)
            else -> account.offsetHours - 8
        }
    }

    private fun simpleDynamicSecret(salt: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val random = SecureRandom()
        val nonce = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
        val seed = "salt=$salt&t=$timestamp&r=$nonce"
        val hash = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$timestamp,$nonce,$hash"
    }

    private fun cookieValue(cookie: String, vararg names: String): String? {
        val values = cookie.split(';').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }.toMap()
        return names.firstNotNullOfOrNull { values[it] }
    }

    private suspend fun announcements(
        account: ApiAccount,
        arguments: Map<String, String>
    ): JSONObject {
        val overseas = if (account.region == ApiRegion.OVERSEAS) account else when (account.game) {
            ApiGame.GENSHIN -> account.withUid("900000005")
            ApiGame.STARRAIL -> account.withUid("809162009")
            ApiGame.ZZZ -> account.withUid("1300000000")
            else -> account
        }
        val (game, gameBiz, level) = when (account.game) {
            ApiGame.GENSHIN -> Triple("hk4e", "hk4e_global", 8)
            ApiGame.STARRAIL -> Triple("hkrpg", "hkrpg_global", 70)
            ApiGame.ZZZ -> Triple("nap", "nap_global", 60)
            else -> throw UnsupportedOperationException("Announcements are not implemented for " + account.game)
        }
        val params = linkedMapOf(
            "game" to game, "game_biz" to gameBiz, "bundle_id" to gameBiz,
            "platform" to "pc", "region" to overseas.server, "uid" to overseas.uid,
            "level" to (arguments["level"] ?: level.toString()), "lang" to (arguments["lang"] ?: language)
        )
        if (account.game == ApiGame.STARRAIL) params["channel_id"] = "1"
        val base = route(ApiScope.ANNOUNCEMENT, account.game, account.region)
        val info = transport.send(
            base, "announcement/api/getAnnList", "", ApiRegion.OVERSEAS, params.getValue("lang"),
            "GET", params, null, rpcHeaders = true, signed = true
        )
        val details = transport.send(
            base, "announcement/api/getAnnContent", "", ApiRegion.OVERSEAS, params.getValue("lang"),
            "GET", params, null, rpcHeaders = true, signed = true
        )
        val detailRows = details.optJSONArray("list") ?: JSONArray()
        val groups = mutableListOf<JSONArray>()
        info.optJSONArray("list")?.let(groups::add)
        info.optJSONArray("pic_list")?.optJSONObject(0)?.optJSONArray("type_list")?.let(groups::add)
        val output = JSONArray()
        val byTitle = linkedMapOf<String, JSONObject>()
        for (group in groups) {
            for (i in 0 until group.length()) {
                val sub = group.optJSONObject(i)?.optJSONArray("list") ?: continue
                for (j in 0 until sub.length()) {
                    val announcement = sub.optJSONObject(j) ?: continue
                    val id = announcement.opt("ann_id")?.toString()
                    val detail = (0 until detailRows.length()).asSequence()
                        .mapNotNull { detailRows.optJSONObject(it) }
                        .firstOrNull { it.opt("ann_id")?.toString() == id }
                    val merged = JSONObject(announcement.toString())
                    if (detail != null) {
                        val keys = detail.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            merged.put(key, detail.opt(key))
                        }
                    }
                    val title = merged.optString("title")
                    val existing = byTitle[title]
                    if (existing != null) {
                        listOf("banner", "img").forEach { key ->
                            if (merged.has(key) && !merged.isNull(key)) existing.put(key, merged.opt(key))
                        }
                    } else {
                        byTitle[title] = merged
                        output.put(merged)
                    }
                }
            }
        }
        return JSONObject().put("list", output)
    }

    private fun isRefreshable(error: HoYoApiException): Boolean =
        error.code in setOf(-100, -101, 10001) ||
            listOf("(-100)", "(-101)", "(10001)").any(error.message.orEmpty()::contains)

    private suspend fun fiveStarWarps(
        cookie: String, account: ApiAccount, method: ApiMethod, arguments: Map<String, String>
    ): JSONObject {
        val warps = JSONArray()
        var page = arguments
        var version = 0
        var pity: Int? = null
        val cursors = hashSetOf<String>()
        while (true) {
            val response = callPage(cookie, account, method, page)
            version = response.optInt("version_id", version)
            for (row in response.optJSONArray("list").objects()) {
                if (row.optJSONObject("got_item") != null || row.optBoolean("got_item")) {
                    if (!row.has("item")) row.optJSONObject("got_item")?.let { item -> row.put("item", item) }
                    warps.put(row)
                } else if (pity == null) pity = row.optInt("gacha_count")
            }
            if (!response.optBoolean("has_more")) break
            val next = response.optString("next_max_id")
            if (next.isBlank() || !cursors.add(next)) break
            page = arguments + mapOf("max_id" to next, "version_id" to version.toString())
        }
        return JSONObject().put("version_id", version).put("pity", pity ?: 0).put("warps", warps)
    }

    private suspend fun generateAuthKey(cookie: String, account: ApiAccount, authAppId: String): String {
        require(account.region == ApiRegion.OVERSEAS) {
            "genshin.py only supports authkey generation for overseas accounts."
        }
        val cookieHash = MessageDigest.getInstance("SHA-256")
            .digest(cookie.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val cacheKey = account.game.name + ":" + account.uid + ":" + authAppId + ":" + cookieHash
        val now = System.currentTimeMillis()
        return authKeyMutex.withLock {
            authKeyCache[cacheKey]?.takeIf { it.first > now }?.second ?: run {
                val activeCookie = sessions.ensureGachaCookie(cookie, account.region)
                val body = JSONObject()
                    .put("game_uid", account.uid)
                    .put("region", account.server)
                    .put("game_biz", gameBiz(account))
                    .put("auth_appid", authAppId)
                val result = transport.send(
                    "https://api-account-os.hoyoverse.com/binding/api",
                    "genAuthKeyByCookieToken",
                    activeCookie,
                    account.region,
                    language,
                    "POST",
                    emptyMap(),
                    body
                )
                val key = result.optString("authkey")
                if (key.isBlank()) throw HoYoApiException("Authkey was not returned by HoYoverse.")
                authKeyCache[cacheKey] = (now + 8L * 60 * 1000) to key
                key
            }
        }
    }

    private suspend fun allTcgDeckComments(
        cookie: String,
        account: ApiAccount,
        method: ApiMethod,
        arguments: Map<String, String>
    ): JSONObject {
        val limit = arguments["limit"]?.toIntOrNull()?.takeIf { it >= 0 }
        val params = method.defaults + (arguments - setOf("limit", "lang"))
        require(!params["entity_key"].isNullOrBlank()) { "デッキを指定してください" }
        var cursor = params["next"] ?: "0"
        val rows = JSONArray()
        if (limit == 0) return JSONObject().put("list", rows)
        val cursors = hashSetOf(cursor)
        while (true) {
            val response = transport.send(
                route(ApiScope.BOULEUTERION, account.game, account.region),
                method.endpoint,
                cookie,
                account.region,
                language,
                "GET",
                params + ("next" to cursor),
                null
            )
            val page = response.optJSONArray("list") ?: JSONArray()
            for (i in 0 until page.length()) {
                if (limit != null && rows.length() >= limit) break
                rows.put(page.opt(i))
            }
            if (limit != null && rows.length() >= limit) return JSONObject().put("list", rows)
            val next = response.optJSONObject("cursor")?.opt("next")?.toString().orEmpty()
            if (page.length() == 0 || next.isBlank() || next == "0" || !cursors.add(next)) {
                return JSONObject().put("list", rows)
            }
            cursor = next
            kotlinx.coroutines.delay(300)
        }
    }

    private suspend fun collectPages(
        cookie: String,
        account: ApiAccount,
        method: ApiMethod,
        arguments: Map<String, String>,
        pageSize: Int
    ): JSONObject {
        val paginator = UpstreamPaginator(this)
        val result = JSONArray()
        val limit = arguments["limit"]?.toIntOrNull()?.takeIf { it >= 0 }
        val query = when (method.name) {
            "get_lineups" -> (arguments - "page_size") + ("limit" to arguments["page_size"].orEmpty())
            "genshin_tcg" -> (arguments - "limit") + ("page_size" to pageSize.toString())
            else -> arguments - "limit"
        }
        val itemField = when (method.name) {
            "teapot_replicas" -> "articles"
            "genshin_tcg" -> "card_list"
            else -> "list"
        }
        val cursorParameter = when (method.name) {
            "claimed_rewards", "genshin_diary_log", "starrail_diary_log", "get_zzz_diary_detail" -> "current_page"
            "get_lineups", "get_tcg_decks" -> "next_page_token"
            else -> "page"
        }
        if (limit == 0) return JSONObject().put("list", result)
        paginator.pages(
            cookie, account, method, query,
            PageSpec(
                cursorParameter = cursorParameter,
                cursorField = if (cursorParameter == "next_page_token") "next_page_token" else "next_end_id",
                itemsField = itemField,
                pageSize = if (cursorParameter == "next_page_token") null else pageSize,
                pauseMilliseconds = 300
            )
        ).takeWhile { page ->
            val rows = page.optJSONArray(itemField) ?: JSONArray()
            for (i in 0 until rows.length()) {
                if (limit != null && result.length() >= limit) break
                result.put(rows.opt(i))
            }
            limit == null || result.length() < limit
        }.collect {}
        return JSONObject().put("list", result)
    }

    private suspend fun allTransactionHistory(
        cookie: String,
        account: ApiAccount,
        method: ApiMethod,
        arguments: Map<String, String>
    ): JSONObject {
        val kinds = arguments["kind"]?.let(::stringList)?.takeIf { it.isNotEmpty() }
            ?: listOf("primogem", "crystal", "resin", "artifact", "weapon")
        val requestedLimit = arguments["limit"]?.toIntOrNull()?.takeIf { it >= 0 }
        if (requestedLimit == 0) return JSONObject().put("list", JSONArray())
        val baseArgs = (arguments - setOf("limit", "kind", "end_id")).let { args ->
            if (args["authkey"].isNullOrBlank()) args + ("authkey" to generateAuthKey(cookie, account, "csc"))
            else args
        }
        val pageMethod = method.copy(name = "transaction_log_page", required = setOf("kind"))
        val records = mutableListOf<JSONObject>()
        val paginator = UpstreamPaginator(this)
        for (kind in kinds.distinct()) {
            val kindRecords = mutableListOf<JSONObject>()
            val pageArgs = baseArgs + mapOf(
                "kind" to kind, "end_id" to (arguments["end_id"] ?: "0"), "size" to "20"
            )
            paginator.pages(
                cookie, account, pageMethod, pageArgs,
                PageSpec(
                    cursorParameter = "end_id",
                    itemsField = "list",
                    pageSize = 20,
                    lastItemCursorField = "id",
                    pauseMilliseconds = 350
                )
            ).takeWhile { page ->
                val rows = page.optJSONArray("list") ?: JSONArray()
                for (item in rows.objects()) {
                    kindRecords.add(item.put("kind", kind))
                }
                requestedLimit == null || kindRecords.size < requestedLimit
            }.collect { }
            records.addAll(kindRecords)
        }
        val sorted = mergeHistoryByTime(listOf(records), requestedLimit)
        return JSONObject().put("list", JSONArray(sorted))
    }

    private suspend fun allGachaHistory(
        cookie: String,
        account: ApiAccount,
        method: ApiMethod,
        arguments: Map<String, String>
    ): JSONObject {
        val (defaultTypes, pageSize) = when (method.name) {
            "wish_history" -> listOf(100, 200, 301, 302, 400, 500) to 20
            "mw_wish_history" -> listOf(1000, 2000, 20011, 20012, 20021, 20022) to 5
            "warp_history" -> listOf(1, 2, 11, 12, 21, 22) to 20
            "signal_history", "chronicle_signal_history" -> listOf(1, 2, 3, 5, 102, 103) to 20
            else -> emptyList<Int>() to 20
        }
        val requestedLimit = arguments["limit"]?.toIntOrNull()?.takeIf { it >= 0 }
        if (requestedLimit == 0) return JSONObject().put("list", JSONArray())
        val types = arguments["gacha_type"]?.let(::intList)?.takeIf { it.isNotEmpty() } ?: defaultTypes
        val perBannerLimit = arguments["per_banner_limit"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: requestedLimit
        val chronicle = method.name == "chronicle_signal_history"
        val itemField = if (chronicle) "gacha_item_list" else "list"
        val baseArgs = (arguments - setOf("limit", "per_banner_limit", "gacha_type", "end_id")).let { args ->
            if (!chronicle && args["authkey"].isNullOrBlank()) {
                args + ("authkey" to generateAuthKey(cookie, account, "webview_gacha"))
            } else args
        }
        val pageMethod = method.copy(name = method.name + "_page", required = setOf("gacha_type"))
        val records = mutableListOf<JSONObject>()
        val paginator = UpstreamPaginator(this)
        for (bannerType in types.distinct()) {
            val kindRecords = mutableListOf<JSONObject>()
            val pageArgs = baseArgs + mapOf(
                "gacha_type" to bannerType.toString(),
                "end_id" to (arguments["end_id"] ?: "0")
            ) + if (chronicle) emptyMap() else mapOf("size" to "20")
            paginator.pages(
                cookie,
                account,
                pageMethod.copy(endpoint = when {
                    method.name == "warp_history" && bannerType in setOf(21, 22) -> "getLdGachaLog"
                    method.name == "mw_wish_history" && bannerType !in setOf(1000, 2000) -> "getGachaLog"
                    else -> pageMethod.endpoint
                }),
                pageArgs,
                PageSpec(
                    cursorParameter = "end_id",
                    itemsField = itemField,
                    pageSize = if (chronicle) null else pageSize,
                    lastItemCursorField = "id",
                    pauseMilliseconds = 350
                )
            ).takeWhile { page ->
                val timezoneOffset = gachaTimezoneOffset(account, page, chronicle)
                for (item in page.optJSONArray(itemField).objects()) {
                    if (chronicle) {
                        val date = item.optJSONObject("date") ?: continue
                        val localTime = runCatching { java.time.LocalDateTime.of(
                            date.getInt("year"), date.getInt("month"), date.getInt("day"),
                            date.getInt("hour"), date.getInt("minute"), date.getInt("second")
                        ) }.getOrNull() ?: continue
                        val rank = mapOf("S" to 4, "A" to 3, "B" to 2)[item.optString("rarity")] ?: continue
                        item.put("time", localTime.toString()).put("rank_type", rank).put("uid", account.uid)
                    }
                    kindRecords.add(item.put("gacha_type", bannerType).put("tz_offset", timezoneOffset))
                }
                perBannerLimit == null || kindRecords.size < perBannerLimit
            }.collect { }
            records.addAll(perBannerLimit?.let(kindRecords::take) ?: kindRecords)
        }
        val unique = records.distinctBy {
            val banner = it.optInt("gacha_type")
            val family = if (account.game == ApiGame.GENSHIN && banner == 400) 301 else banner
            family to it.optString("id").ifBlank { it.toString() }
        }
        val sorted = mergeHistoryByTime(listOf(unique), requestedLimit)
        return JSONObject().put("list", JSONArray(sorted))
    }

    private fun gameBiz(account: ApiAccount): String {
        val suffix = if (account.region == ApiRegion.CHINESE) "cn" else "global"
        val prefix = when (account.game) {
            ApiGame.GENSHIN -> "hk4e"
            ApiGame.STARRAIL -> "hkrpg"
            ApiGame.ZZZ -> "nap"
            ApiGame.HONKAI -> "bh3"
            ApiGame.TOT -> "nxx"
        }
        return "${prefix}_${suffix}"
    }

    private fun jsonBody(params: Map<String, String>): JSONObject = JSONObject().apply {
        for ((key, value) in params) {
            val parsed: Any = when {
                key in setOf(
                    "avatar_list", "entry_page_ids", "character_ids", "types", "role_ids", "role_card_id",
                    "element_attr_ids", "weapon_cat_ids", "weapon_levels", "reliquary_levels",
                    "reliquary_list", "skill_list", "items", "list", "filters"
                ) ->
                    try { JSONArray(value) } catch (_: Exception) { value }
                key == "weapon" -> JSONObject(value)
                key in setOf("is_first_brush", "is_cancel", "sync_all_roles", "use_es") ->
                    value.equals("true", ignoreCase = true)
                key in setOf("game_id", "role_id", "page_size", "page", "size", "task_id", "award_id",
                    "avatar_id", "avatar_level_current", "avatar_level_target", "element_attr_id",
                    "reliquary_cat_id", "cat_id", "menu_id", "page_num", "label_id") ->
                    value.toLongOrNull() ?: value
                else -> value
            }
            put(key, parsed)
        }
    }

    private fun route(scope: ApiScope, game: ApiGame, region: ApiRegion): String {
        val cn = region == ApiRegion.CHINESE
        return when (scope) {
            ApiScope.RECORD -> {
                val prefix = "https://api-takumi-record.mihoyo.com"
                when (game) {
                    ApiGame.GENSHIN -> if (cn) prefix + "/game_record/app/genshin/api"
                        else "https://sg-public-api.hoyolab.com/event/game_record/genshin/api"
                    ApiGame.STARRAIL -> if (cn) prefix + "/game_record/app/hkrpg/api"
                        else "https://bbs-api-os.hoyolab.com/game_record/hkrpg/api"
                    ApiGame.ZZZ -> if (cn) prefix + "/event/game_record_zzz/api/zzz"
                        else "https://sg-act-public-api.hoyolab.com/event/game_record_zzz/api/zzz"
                    ApiGame.HONKAI -> if (cn) prefix + "/game_record/app/honkai3rd/api"
                        else "https://bbs-api-os.hoyolab.com/game_record/honkai3rd/api"
                    ApiGame.TOT -> throw UnsupportedOperationException("No Battle Chronicle route for Tears of Themis.")
                }
            }
            ApiScope.CARD -> if (cn) "https://api-takumi-record.mihoyo.com/game_record/app/card/wapi"
                else "https://bbs-api-os.hoyolab.com/game_record/card/wapi"
            ApiScope.LEDGER -> when (game) {
                ApiGame.GENSHIN -> if (cn) "https://hk4e-api.mihoyo.com/event/ys_ledger"
                    else "https://sg-hk4e-api.hoyolab.com/event/ysledgeros"
                ApiGame.STARRAIL -> if (cn) "https://api-takumi.mihoyo.com/event/srledger"
                    else "https://sg-public-api.hoyolab.com/event/srledger"
                else -> throw UnsupportedOperationException("Ledger unsupported for " + game)
            }
            ApiScope.ZZZ_LEDGER -> "https://sg-public-api.hoyolab.com/event/nap_ledger"
            ApiScope.CULTIVATE -> if (cn) "https://api-takumi.mihoyo.com/event/nap_cultivate_tool"
                else "https://sg-act-public-api.hoyolab.com/event/nap_cultivate_tool"
            ApiScope.REWARD -> if (cn) {
                when (game) {
                    ApiGame.GENSHIN -> "https://api-takumi.mihoyo.com/event/luna/hk4e?act_id=e202311201442471"
                    ApiGame.HONKAI -> "https://api-takumi.mihoyo.com/event/luna/bh3?act_id=e202306201626331"
                    ApiGame.STARRAIL -> "https://api-takumi.mihoyo.com/event/luna/hkrpg?act_id=e202304121516551"
                    ApiGame.ZZZ -> "https://api-takumi.mihoyo.com/event/luna/zzz?act_id=e202406242138391"
                    ApiGame.TOT -> "https://api-takumi.mihoyo.com/event/luna/nxx?act_id=e202202251749321"
                }
            } else {
                when (game) {
                    ApiGame.GENSHIN -> "https://sg-hk4e-api.hoyolab.com/event/sol?act_id=e202102251931481"
                    ApiGame.HONKAI -> "https://sg-public-api.hoyolab.com/event/mani?act_id=e202110291205111"
                    ApiGame.STARRAIL -> "https://sg-public-api.hoyolab.com/event/luna/hkrpg/os?act_id=e202303301540311"
                    ApiGame.ZZZ -> "https://sg-public-api.hoyolab.com/event/luna/zzz/os?act_id=e202406031448091"
                    ApiGame.TOT -> "https://sg-public-api.hoyolab.com/event/luna/nxx/os?act_id=e202202281857121"
                }
            }
            ApiScope.CALCULATOR -> if (cn) "https://api-takumi.mihoyo.com/event/e20200928calculate"
                else "https://sg-public-api.hoyolab.com/event/e20200928calculate"
            ApiScope.LINEUP -> if (cn) "https://api-takumi.mihoyo.com/event/platsimulator"
                else "https://sg-public-api.hoyoverse.com/event/simulatoros"
            ApiScope.HSR_LINEUP -> "https://sg-public-api.hoyolab.com/event/rpgsimulator"
            ApiScope.HSR_WARP -> if (cn) "https://api-takumi.mihoyo.com/event/rpg_gacha_record"
                else "https://sg-act-public-api.hoyolab.com/event/rpg_gacha_record"
            ApiScope.CARD_PLAZA -> "https://sg-act-public-api.hoyolab.com/event/cardsquare"
            ApiScope.BOULEUTERION -> "https://sg-act-public-api.hoyolab.com/bouleuterion_v2/v1"
            ApiScope.TEAPOT -> if (cn) throw UnsupportedOperationException("Teapot has no Chinese route.")
                else "https://sg-hk4e-api.hoyolab.com/event/e20221121ugcos"
            ApiScope.WIKI -> "https://sg-wiki-api.hoyolab.com/hoyowiki/wapi"
            ApiScope.GACHA -> when (game) {
                ApiGame.GENSHIN -> if (cn) "https://public-operation-hk4e.mihoyo.com/gacha_info/api"
                    else "https://public-operation-hk4e-sg.hoyoverse.com/gacha_info/api"
                ApiGame.STARRAIL -> if (cn) "https://public-operation-hkrpg.mihoyo.com/common/gacha_record/api"
                    else "https://public-operation-hkrpg-sg.hoyoverse.com/common/gacha_record/api"
                ApiGame.ZZZ -> if (cn) "https://public-operation-nap.mihoyo.com/common/gacha_record/api"
                    else "https://public-operation-nap-sg.hoyoverse.com/common/gacha_record/api"
                else -> throw UnsupportedOperationException("Gacha unsupported for " + game)
            }
            ApiScope.TRANSACTION -> if (cn) "https://hk4e-api.mihoyo.com/common/hk4e_self_help_query/User"
                else "https://hk4e-api-os.hoyoverse.com/common/hk4e_self_help_query/User"
            ApiScope.ACCOUNTS -> if (cn) "https://api-takumi.mihoyo.com" else "https://api-os-takumi.mihoyo.com"
            ApiScope.BBS -> if (cn) "https://bbs-api.mihoyo.com"
                else "https://bbs-api-os.hoyolab.com"
            ApiScope.MIMO -> "https://sg-public-api.hoyolab.com/event/e2023mimotravel"
            ApiScope.WEBSTATIC -> if (cn) "https://operation-webstatic.mihoyo.com"
                else "https://operation-webstatic.hoyoverse.com"
            ApiScope.ANNOUNCEMENT -> when (game) {
                ApiGame.GENSHIN -> "https://sg-hk4e-api.hoyoverse.com/common/hk4e_global"
                ApiGame.STARRAIL -> "https://sg-hkrpg-api.hoyoverse.com/common/hkrpg_global"
                ApiGame.ZZZ -> "https://sg-announcement-static.hoyoverse.com/common/nap_global"
                else -> throw UnsupportedOperationException("No announcements for " + game)
            }
        }
    }
}

class ApiTransport internal constructor(
    private val onCookieUpdate: ((String) -> Unit)?,
    private val openConnection: (URL) -> HttpURLConnection
) {
    constructor(onCookieUpdate: ((String) -> Unit)? = null) :
        this(onCookieUpdate, { it.openConnection() as HttpURLConnection })

    private val random = SecureRandom()
    private val userAgent = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"

    suspend fun send(
        base: String,
        endpoint: String,
        cookie: String,
        region: ApiRegion,
        language: String,
        verb: String,
        params: Map<String, String>,
        body: JSONObject?,
        headers: Map<String, String> = emptyMap(),
        rpcHeaders: Boolean = false,
        signed: Boolean = false,
        repeated: Map<String, List<String>> = emptyMap()
    ): JSONObject {
        val exchange = exchange(base, endpoint, cookie, region, language, verb, params, body, headers, rpcHeaders, signed, repeated)
        if (cookie.isNotBlank()) mergeSetCookies(cookie, exchange.headers)
        exchange.requireSuccess()
        return when (val result = exchange.payload.opt("data")) {
            is JSONObject -> result
            is JSONArray -> JSONObject().put("list", result)
            else -> exchange.payload
        }
    }

    internal suspend fun exchange(
        base: String,
        endpoint: String,
        cookie: String,
        region: ApiRegion,
        language: String,
        verb: String,
        params: Map<String, String>,
        body: JSONObject?,
        headers: Map<String, String> = emptyMap(),
        rpcHeaders: Boolean = false,
        signed: Boolean = false,
        repeated: Map<String, List<String>> = emptyMap()
    ): ApiExchange = withContext(Dispatchers.IO) {
        require(verb == "GET" || verb == "POST")
        val url = ApiUrlComposer.compose(base, endpoint, params, repeated)
        val connection = openConnection(url).apply {
            requestMethod = verb
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = false
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            if (rpcHeaders) {
                setRequestProperty("x-rpc-language", language)
                setRequestProperty("x-rpc-lang", language)
            }
            if (signed) {
                setRequestProperty("x-rpc-app_version", if (region == ApiRegion.CHINESE) "2.11.1" else "1.5.0")
                setRequestProperty("x-rpc-client_type", "5")
                setRequestProperty("DS", dynamicSecret(region, body, params))
            }
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val payload = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw HoYoApiException("HTTP " + status, httpStatus = status)
            ApiExchange(JSONObject(payload), connection.headerFields.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun mergeSetCookies(cookie: String, headers: Map<String?, List<String>>?) {
        val accepted = setOf(
            "ltuid", "ltoken", "ltuid_v2", "ltoken_v2", "ltmid_v2",
            "account_id", "account_id_v2", "account_mid_v2",
            "cookie_token", "cookie_token_v2", "stoken", "stoken_v2",
            "login_ticket", "login_ticket_token", "e_nap_token", "e_hkrpg_token",
            "e_lrsag", "SERVERID", "SERVERCORSID", "mi18nLang"
        )
        val merged = linkedMapOf<String, String>()
        cookie.split(';').forEach { part ->
            val index = part.indexOf('=')
            if (index > 0) {
                val name = part.substring(0, index).trim()
                val value = part.substring(index + 1).trim()
                if (name in accepted && value.isNotEmpty()) merged[name] = value
            }
        }
        headers.orEmpty().filterKeys { it.equals("set-cookie", ignoreCase = true) }
            .values.flatten().forEach { header ->
                val part = header.substringBefore(';').trim()
                val index = part.indexOf('=')
                if (index > 0) {
                    val name = part.substring(0, index).trim()
                    val value = part.substring(index + 1).trim()
                    if (name in accepted) {
                        if (value.isEmpty()) merged.remove(name) else merged[name] = value
                    }
                }
            }
        val updated = merged.entries.joinToString("; ") { it.key + "=" + it.value }
        if (updated.isNotBlank() && updated != cookie) onCookieUpdate?.invoke(updated)
    }

    private fun jsonForSignature(value: Any?): String = when (value) {
        is JSONObject -> {
            val keys = value.keys().asSequence().toList()
            keys.joinToString(", ", "{", "}") { key ->
                JSONObject.quote(key) + ": " + jsonForSignature(value.opt(key))
            }
        }
        is JSONArray -> (0 until value.length()).joinToString(", ", "[", "]") {
            jsonForSignature(value.opt(it))
        }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun dynamicSecret(
        region: ApiRegion,
        body: JSONObject?,
        params: Map<String, String>
    ): String {
        val timestamp = System.currentTimeMillis() / 1000
        val salt: String
        val nonce: String
        val seed: String
        if (region == ApiRegion.OVERSEAS) {
            salt = "6s25p5ox5y14umn1p61aqyyvbvvl3lrt"
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            nonce = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
            seed = "salt=" + salt + "&t=" + timestamp + "&r=" + nonce
        } else {
            salt = "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs"
            nonce = (100001 + random.nextInt(100000)).toString()
            val query = params.toSortedMap().entries.joinToString("&") { it.key + "=" + it.value }
            seed = "salt=" + salt + "&t=" + timestamp + "&r=" + nonce + "&b=" +
                (body?.let(::jsonForSignature) ?: "") + "&q=" + query
        }
        val hash = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return timestamp.toString() + "," + nonce + "," + hash
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

internal class ApiExchange(val payload: JSONObject, val headers: Map<String?, List<String>>) {
    val code: Int get() = payload.optInt("retcode", payload.optInt("code", 0))

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    fun requireSuccess() {
        if (code != 0) throw HoYoApiException(payload.optString("message").ifBlank { "通信エラー" } + " (" + code + ")", code)
    }
}

internal fun withStuid(cookie: String): String {
    val items = cookie.split(';').map(String::trim)
    if (items.none { it.startsWith("stoken=") || it.startsWith("stoken_v2=") } ||
        items.any { it.startsWith("stuid=") }) return cookie
    val id = listOf("ltuid_v2", "account_id_v2", "ltuid", "account_id")
        .firstNotNullOfOrNull { name -> items.firstOrNull { it.startsWith("$name=") }?.substringAfter('=') }
    return if (id.isNullOrBlank()) cookie else "$cookie; stuid=$id"
}

private val uidScoped = setOf(
    ApiScope.RECORD, ApiScope.LEDGER, ApiScope.ZZZ_LEDGER, ApiScope.CULTIVATE,
    ApiScope.HSR_WARP, ApiScope.HSR_LINEUP, ApiScope.ANNOUNCEMENT, ApiScope.CALCULATOR
)
private val realTimeNotes = setOf("get_genshin_notes", "get_starrail_notes", "get_zzz_notes")
private val upstreamAliases = mapOf(
    "get_diary" to "get_genshin_diary", "diary_log" to "genshin_diary_log",
    "get_record_card" to "get_record_cards", "get_banner_names" to "get_genshin_banner_names",
    "get_banner_ids" to "get_genshin_banner_ids", "get_spiral_abyss" to "get_genshin_spiral_abyss",
    "get_notes" to "get_genshin_notes", "get_activities" to "get_genshin_activities",
    "get_apocalyptic_shadow" to "get_starrail_apc_shadow", "get_old_abyss" to "get_honkai_old_abyss",
    "get_superstring_abyss" to "get_honkai_superstring_abyss"
)
