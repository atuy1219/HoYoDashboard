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
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar

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
    private val badges: BadgeSession = BadgeSession(),
    private val sessions: CookieSessionManager = CookieSessionManager(onCookieUpdate)
) {
    private val authKeyMutex = Mutex()
    private val authKeyCache = mutableMapOf<String, Pair<Long, String>>()

    fun available(game: ApiGame): List<ApiMethod> =
        UpstreamCatalog.methods.filter { it.game == null || it.game == game }

    suspend fun call(
        cookie: String,
        account: ApiAccount,
        name: String,
        arguments: Map<String, String> = emptyMap()
    ): JSONObject {
        val method = UpstreamCatalog.find(name)
            ?: throw IllegalArgumentException("Unknown upstream method: " + name)
        return call(cookie, account, method, arguments)
    }

    suspend fun call(
        cookie: String,
        account: ApiAccount,
        method: ApiMethod,
        arguments: Map<String, String> = emptyMap()
    ): JSONObject {
        require(method.readOnly) { "Write operations require a separate explicit API." }
        require(method.game == null || method.game == account.game) {
            "Endpoint not supported for " + account.game
        }
        if (method.scope == ApiScope.ANNOUNCEMENT) {
            return announcements(cookie, account, method)
        }
        if (method.name in setOf("wish_history", "mw_wish_history", "warp_history", "signal_history") &&
            arguments["gacha_type"].isNullOrBlank()
        ) {
            return allGachaHistory(cookie, account, method, arguments)
        }
        if (method.name == "transaction_log" && arguments["kind"].isNullOrBlank()) {
            return allTransactionHistory(cookie, account, method, arguments)
        }
        val pageSizes = mapOf(
            "claimed_rewards" to 10,
            "genshin_diary_log" to 100,
            "starrail_diary_log" to 100,
            "get_zzz_diary_detail" to 100,
            "get_lineups" to 20,
            "get_starrail_lineups" to 20,
            "get_tcg_decks" to 20,
            "teapot_replicas" to 20,
            "get_mimo_point_history" to 20
        )
        pageSizes[method.name]?.let { pageSize ->
            return collectPages(cookie, account, method, arguments, pageSize)
        }
        if (method.name == "get_full_genshin_user") {
            val user = call(cookie, account, "get_genshin_user")
            val current = call(cookie, account, "get_genshin_spiral_abyss")
            val previous = call(cookie, account, "get_genshin_spiral_abyss_previous")
            val activities = call(cookie, account, "get_genshin_activities")
            return JSONObject().put("user", user).put("spiral_abyss", current)
                .put("previous_spiral_abyss", previous).put("activities", activities)
        }
        if (method.name == "get_honkai_abyss") {
            val old = call(cookie, account, "get_honkai_old_abyss")
            val current = call(cookie, account, "get_honkai_superstring_abyss")
            return JSONObject().put("old_abyss", old).put("superstring_abyss", current)
        }
        if (method.name == "get_full_honkai_user") {
            val user = call(cookie, account, "get_honkai_user")
            val suits = call(cookie, account, "get_honkai_battlesuits")
            val abyss = call(cookie, account, "get_honkai_abyss")
            val arena = call(cookie, account, "get_honkai_memorial_arena")
            val realm = call(cookie, account, "get_honkai_elysian_realm")
            return JSONObject().put("user", user).put("battlesuits", suits).put("abyss", abyss)
                .put("memorial_arena", arena).put("elysian_realm", realm)
        }
        if (method.name == "get_all_zzz_agent_upgrade_guides") {
            val agents = call(cookie, account, "get_zzz_upgrade_guide_agents")
            val rows = agents.optJSONArray("list") ?: JSONArray()
            val results = JSONArray()
            val ids = (0 until rows.length()).mapNotNull {
                rows.optJSONObject(it)?.optInt("id", 0)?.takeIf { id -> id > 0 }
            }
            for (batch in ids.chunked(10)) {
                val avatarList = JSONArray()
                batch.forEach { id ->
                    avatarList.put(JSONObject().put("avatar_id", id).put("is_teaser", false)
                        .put("teaser_need_weapon", false).put("teaser_sp_skill", false))
                }
                val guides = call(cookie, account, "get_zzz_agent_upgrade_guide",
                    mapOf("avatar_list" to avatarList.toString()))
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
            val characters = call(cookie, account, "get_genshin_characters")
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
            val agents = call(cookie, account, "get_zzz_agents")
            val rows = agents.optJSONArray("avatar_list") ?: agents.optJSONArray("list") ?: JSONArray()
            val details = JSONArray()
            for (i in 0 until rows.length()) {
                val id = rows.optJSONObject(i)?.optInt("id", 0) ?: 0
                if (id <= 0) continue
                val result = call(
                    cookie, account, method.copy(name = "get_zzz_agent_info_raw"),
                    arguments + ("id_list[]" to id.toString())
                )
                details.put(result.optJSONArray("avatar_list")?.optJSONObject(0) ?: result)
            }
            return JSONObject().put("avatar_list", details)
        }
        if (method.name == "get_threshold_simulation" && arguments["void_front_id"].isNullOrBlank()) {
            val brief = call(cookie, account, "get_threshold_simulation_brief")
            val id = brief.optString("id").ifBlank { brief.optString("void_front_id") }
            if (id.isBlank()) return brief
            return call(
                cookie, account, method.copy(name = "get_threshold_simulation_raw"),
                arguments + ("void_front_id" to id)
            )
        }
        if (method.name == "get_mimo_point_count") {
            val data = call(cookie, account, "get_mimo_games")
            val rows = data.optJSONArray("list") ?: data.optJSONArray("act_list") ?: JSONArray()
            val targetId = when (account.game) {
                ApiGame.GENSHIN -> 2
                ApiGame.STARRAIL -> 6
                ApiGame.ZZZ -> 8
                ApiGame.HONKAI -> -1
                ApiGame.TOT -> -1
            }
            val item = (0 until rows.length()).asSequence().mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                row.optJSONObject("act_info") ?: row
            }.firstOrNull { it.optInt("game_id", it.optInt("id")) == targetId }
                ?: throw HoYoApiException("No Mimo point account for " + account.game)
            return JSONObject().put("point", item.optInt("point"))
        }
        val params = method.defaults.mapValues { (_, value) ->
            value.replace("{uid}", account.uid).replace("{server}", account.server)
        }.toMutableMap()
        params.putAll(arguments)
        if (method.name == "get_record_cards" && params["uid"].isNullOrBlank()) {
            val cookieValues = cookie.split(';').mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }.toMap()
            params["uid"] = cookieValues["ltuid_v2"] ?: cookieValues["ltuid"]
                ?: cookieValues["account_id_v2"] ?: cookieValues["account_id"]
                ?: throw IllegalArgumentException("HoYoLAB account ID is missing from cookies.")
        }
        val missing = method.required.filter { key ->
            params[key].isNullOrBlank() || params[key]!!.startsWith("{")
        }
        require(missing.isEmpty()) { "Missing parameters: " + missing.joinToString() }
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
        val requestRegion = if (method.name == "get_genshin_banner_ids") ApiRegion.CHINESE else account.region
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
        if (method.scope == ApiScope.ZZZ_LEDGER || method.scope == ApiScope.CULTIVATE) {
            params["uid"] = account.uid
            params["region"] = account.server
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
                params["lang"] = language
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
            params.putIfAbsent(
                "month",
                if (account.game == ApiGame.STARRAIL) {
                    java.text.SimpleDateFormat("yyyyMM", java.util.Locale.ROOT).format(java.util.Date())
                } else (Calendar.getInstance().get(Calendar.MONTH) + 1).toString()
            )
            params["lang"] = language
        }
        if (method.scope in setOf(ApiScope.LINEUP, ApiScope.CARD_PLAZA, ApiScope.TEAPOT, ApiScope.MIMO)) {
            params.putIfAbsent("lang", language)
        }
        if (method.scope == ApiScope.HSR_WARP) {
            params["role_id"] = account.uid
            params["server"] = account.server
            params["game_biz"] = if (account.region == ApiRegion.CHINESE) "hkrpg_cn" else "hkrpg_global"
        }
        if (method.scope == ApiScope.GACHA) {
            params["authkey_ver"] = "1"
            params["authkey"] = java.net.URLDecoder.decode(params["authkey"].orEmpty().replace("+", "%2B"), "UTF-8")
            params["lang"] = if (method.endpoint == "getBeyondGachaLog") language else language.substringBefore('-')
            params["game_biz"] = gameBiz(account)
            params.putIfAbsent("size", "20")
            params.putIfAbsent("page", "1")
            params.putIfAbsent("end_id", "0")
            params["real_gacha_type"] = params["gacha_type"] ?: error("gacha_type is required")
        }
        if (method.scope == ApiScope.TRANSACTION) {
            params["authkey_ver"] = "1"
            params["sign_type"] = "2"
            params["authkey"] = java.net.URLDecoder.decode(params["authkey"].orEmpty().replace("+", "%2B"), "UTF-8")
            params["lang"] = language.substringBefore('-')
            params.putIfAbsent("size", "20")
            params.putIfAbsent("end_id", "0")
        }
        if (method.scope == ApiScope.WIKI) {
            params.putIfAbsent("lang", language)
            params.putIfAbsent("page_num", "1")
            params.putIfAbsent("page_size", "1000")
        }
        val path = when {
            method.name == "get_hoyolab_user" && account.region == ApiRegion.CHINESE ->
                "user/wapi/getUserFullInfo"
            method.name == "get_banner_details" -> {
                require(account.game == ApiGame.GENSHIN || account.game == ApiGame.STARRAIL)
                val gameName = if (account.game == ApiGame.STARRAIL) "hkrpg" else "hk4e"
                val serverName = if (account.game == ApiGame.STARRAIL) "prod_official_asia" else "os_asia"
                method.endpoint.replace("{game}", gameName)
                    .replace("{server}", serverName)
                    .replace("{banner_id}", params["banner_id"].orEmpty())
                    .replace("{lang}", language)
            }
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
        val query = if (post) {
            if (method.scope == ApiScope.CULTIVATE) {
                mapOf("uid" to account.uid, "region" to account.server)
            } else emptyMap()
        } else params
        val body = if (post) jsonBody(params) else null
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
        val activeCookie = if (method.requiresBadge) {
            try {
                badges.cookieFor(cookie, account, language)
            } catch (error: HoYoApiException) {
                if (!isRefreshable(error)) throw error
                val refreshed = sessions.refresh(cookie, account.region) ?: throw error
                badges.invalidate(account)
                badges.cookieFor(refreshed, account, language)
            }
        } else cookie
        val rpcHeaders = method.scope in setOf(
            ApiScope.RECORD, ApiScope.CARD, ApiScope.BBS, ApiScope.ANNOUNCEMENT,
            ApiScope.CULTIVATE, ApiScope.HSR_WARP, ApiScope.HSR_LINEUP
        )
        val signed = method.scope in setOf(
            ApiScope.RECORD, ApiScope.CARD, ApiScope.BBS, ApiScope.ANNOUNCEMENT, ApiScope.HSR_LINEUP
        ) || ((method.scope == ApiScope.CULTIVATE || method.scope == ApiScope.HSR_WARP) &&
            account.region == ApiRegion.CHINESE)
        return try {
            transport.send(
                base, path, activeCookie, requestRegion, language,
                if (post) "POST" else "GET", query, body, headers,
                rpcHeaders = rpcHeaders, signed = signed
            )
        } catch (error: HoYoApiException) {
            if (!isRefreshable(error)) throw error
            if (method.requiresBadge) badges.invalidate(account)
            val refreshedBase = sessions.refresh(cookie, account.region) ?: throw error
            val refreshed = if (method.requiresBadge) {
                badges.cookieFor(refreshedBase, account, language)
            } else refreshedBase
            transport.send(
                base, path, refreshed, requestRegion, language,
                if (post) "POST" else "GET", query, body, headers,
                rpcHeaders = rpcHeaders, signed = signed
            )
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
        cookie: String,
        account: ApiAccount,
        method: ApiMethod
    ): JSONObject {
        require(account.region == ApiRegion.OVERSEAS) {
            "The upstream announcement client currently uses global game announcement hosts."
        }
        val (game, gameBiz, level) = when (account.game) {
            ApiGame.GENSHIN -> Triple("hk4e", "hk4e_global", 8)
            ApiGame.STARRAIL -> Triple("hkrpg", "hkrpg_global", 70)
            ApiGame.ZZZ -> Triple("nap", "nap_global", 60)
            else -> throw UnsupportedOperationException("Announcements are not implemented for " + account.game)
        }
        val params = linkedMapOf(
            "game" to game, "game_biz" to gameBiz, "bundle_id" to gameBiz,
            "platform" to "pc", "region" to account.server, "uid" to account.uid,
            "level" to level.toString(), "lang" to language
        )
        if (account.game == ApiGame.STARRAIL) params["channel_id"] = "1"
        val base = route(ApiScope.ANNOUNCEMENT, account.game, account.region)
        val info = transport.send(
            base, "announcement/api/getAnnList", cookie, account.region, language,
            "GET", params, null, rpcHeaders = true, signed = true
        )
        val details = transport.send(
            base, "announcement/api/getAnnContent", cookie, account.region, language,
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

    private fun isRefreshable(error: HoYoApiException): Boolean {
        val message = error.message.orEmpty()
        return listOf("(-100)", "(-101)", "(10001)", "(10101)").any(message::contains)
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

    private suspend fun collectPages(
        cookie: String,
        account: ApiAccount,
        method: ApiMethod,
        arguments: Map<String, String>,
        pageSize: Int
    ): JSONObject {
        val paginator = UpstreamPaginator(this)
        val result = JSONArray()
        paginator.pages(
            cookie, account, method, arguments,
            PageSpec(
                cursorParameter = "current_page",
                itemsField = "list",
                pageSize = pageSize,
                maxPages = 100,
                pauseMilliseconds = 300
            )
        ).collect { page ->
            val rows = page.optJSONArray("list") ?: JSONArray()
            for (i in 0 until rows.length()) result.put(rows.opt(i))
        }
        return JSONObject().put("list", result)
    }

    private suspend fun allTransactionHistory(
        cookie: String,
        account: ApiAccount,
        method: ApiMethod,
        arguments: Map<String, String>
    ): JSONObject {
        val kinds = listOf("primogem", "crystal", "resin", "artifact", "weapon")
        val requestedLimit = arguments["limit"]?.toIntOrNull()?.takeIf { it > 0 }
        val baseArgs = arguments - setOf("limit", "kind", "end_id")
        val pageMethod = method.copy(name = "transaction_log_page", required = setOf("kind"))
        val records = mutableListOf<JSONObject>()
        val paginator = UpstreamPaginator(this)
        for (kind in kinds) {
            val pageArgs = baseArgs + mapOf("kind" to kind, "end_id" to "0", "size" to "20")
            paginator.pages(
                cookie, account, pageMethod, pageArgs,
                PageSpec(
                    cursorParameter = "end_id",
                    itemsField = "list",
                    pageSize = 20,
                    lastItemCursorField = "id",
                    maxPages = 100,
                    pauseMilliseconds = 350
                )
            ).takeWhile { page ->
                val rows = page.optJSONArray("list") ?: JSONArray()
                for (i in 0 until rows.length()) rows.optJSONObject(i)?.let(records::add)
                requestedLimit == null || records.size < requestedLimit
            }.collect { }
            if (requestedLimit != null && records.size >= requestedLimit) break
        }
        val sorted = records.sortedByDescending { it.optString("time") }
            .let { if (requestedLimit == null) it else it.take(requestedLimit) }
        return JSONObject().put("list", JSONArray(sorted))
    }

    private suspend fun allGachaHistory(
        cookie: String,
        account: ApiAccount,
        method: ApiMethod,
        arguments: Map<String, String>
    ): JSONObject {
        val (types, pageSize) = when (method.name) {
            "wish_history" -> listOf(100, 200, 301, 302, 400, 500) to 20
            "mw_wish_history" -> listOf(1000, 2000, 20011, 20012, 20021, 20022) to 5
            "warp_history" -> listOf(1, 2, 11, 12, 21, 22) to 20
            "signal_history" -> listOf(1, 2, 3, 5, 102, 103) to 20
            else -> emptyList<Int>() to 20
        }
        val requestedLimit = arguments["limit"]?.toIntOrNull()?.takeIf { it > 0 }
        val baseArgs = arguments - setOf("limit", "gacha_type", "end_id")
        val pageMethod = method.copy(name = method.name + "_page", required = setOf("gacha_type"))
        val records = mutableListOf<JSONObject>()
        val paginator = UpstreamPaginator(this)
        for (bannerType in types) {
            val pageArgs = baseArgs + mapOf(
                "gacha_type" to bannerType.toString(),
                "end_id" to "0",
                "size" to pageSize.toString()
            )
            paginator.pages(
                cookie,
                account,
                pageMethod,
                pageArgs,
                PageSpec(
                    cursorParameter = "end_id",
                    itemsField = "list",
                    pageSize = pageSize,
                    lastItemCursorField = "id",
                    maxPages = 100,
                    pauseMilliseconds = 350
                )
            ).takeWhile { page ->
                val rows = page.optJSONArray("list") ?: JSONArray()
                for (i in 0 until rows.length()) {
                    rows.optJSONObject(i)?.let(records::add)
                }
                requestedLimit == null || records.size < requestedLimit
            }.collect { }
            if (requestedLimit != null && records.size >= requestedLimit) break
        }
        val sorted = records.sortedByDescending { it.optString("time") }
            .let { if (requestedLimit == null) it else it.take(requestedLimit) }
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
                key in setOf("avatar_list", "entry_page_ids", "character_ids", "types") ->
                    try { JSONArray(value) } catch (_: Exception) { value }
                key in setOf("is_first_brush", "is_cancel", "sync_all_roles") ->
                    value.equals("true", ignoreCase = true)
                key in setOf("game_id", "role_id", "page_size", "page", "size", "task_id", "award_id") ->
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
            ApiScope.ZZZ_LEDGER -> if (cn) throw UnsupportedOperationException("ZZZ ledger route is not available for the Chinese region.")
                else "https://sg-public-api.hoyolab.com/event/nap_ledger"
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

class ApiTransport(
    private val onCookieUpdate: ((String) -> Unit)? = null
) {
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
        signed: Boolean = false
    ): JSONObject = withContext(Dispatchers.IO) {
        require(verb == "GET" || verb == "POST")
        val url = ApiUrlComposer.compose(base, endpoint, params)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = verb
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = false
            setRequestProperty("Cookie", cookie)
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
            if (status !in 200..299) throw HoYoApiException("HTTP " + status)
            val response = JSONObject(payload)
            val code = response.optInt("retcode", response.optInt("code", 0))
            mergeSetCookies(cookie, connection.headerFields)
            if (code != 0) {
                throw HoYoApiException(response.optString("message").ifBlank { "API error " + code } + " (" + code + ")")
            }
            when (val result = response.opt("data")) {
                is JSONObject -> result
                is JSONArray -> JSONObject().put("list", result)
                else -> response
            }
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
