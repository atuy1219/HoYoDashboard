package com.atuy.hoyodashboard.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

class HoYoClient(
    private val onCookieUpdate: ((String) -> Unit)? = null,
    private val language: String = "ja-jp"
) {
    suspend fun dashboard(cookie: String): List<DashboardItem> = coroutineScope {
        accounts(cookie).map { role ->
            async {
                try {
                    if (role.game == Game.TOT) {
                        DashboardItem(role, null, "未定事件簿にはリアルタイムノートがありません")
                    } else {
                        DashboardItem(role, notes(cookie, role), null)
                    }
                } catch (error: Exception) {
                    DashboardItem(role, null, error.message ?: "取得失敗")
                }
            }
        }.awaitAll()
    }

    suspend fun accounts(cookie: String): List<Role> = coroutineScope {
        listOf(ApiRegion.OVERSEAS, ApiRegion.CHINESE).map { region ->
            async {
                runCatching { accounts(cookie, region) }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
            .distinctBy { Triple(it.game, it.uid, it.region) }
            .sortedWith(compareBy<Role> { it.game.ordinal }.thenByDescending { it.level })
    }

    private suspend fun accounts(cookie: String, region: ApiRegion): List<Role> {
        val host = if (region == ApiRegion.CHINESE) {
            "https://api-takumi.mihoyo.com/binding/api/getUserGameRolesByCookie"
        } else "https://api-os-takumi.mihoyo.com/binding/api/getUserGameRolesByCookie"
        val data = fetch(host, cookie, region)
        val roles = data.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until roles.length()) {
                val value = roles.optJSONObject(i) ?: continue
                val gameBiz = value.optString("game_biz").lowercase()
                val game = when {
                    gameBiz.startsWith("hk4e_") -> Game.GENSHIN
                    gameBiz.startsWith("hkrpg_") -> Game.STAR_RAIL
                    gameBiz.startsWith("nap_") -> Game.ZZZ
                    gameBiz.startsWith("bh3_") -> Game.HONKAI
                    gameBiz.startsWith("nxx_") -> Game.TOT
                    else -> null
                } ?: continue
                val uid = value.optString("game_uid")
                val accountRegion = when {
                    gameBiz.endsWith("_cn") -> ApiRegion.CHINESE
                    gameBiz.endsWith("_global") -> ApiRegion.OVERSEAS
                    else -> region
                }
                val server = value.optString("region").ifBlank {
                    serverFor(game, uid, accountRegion)
                }
                if (uid.isNotBlank() && server.isNotBlank() &&
                    none { it.game == game && it.uid == uid && it.region == accountRegion }
                ) {
                    add(
                        Role(
                            game = game,
                            uid = uid,
                            nickname = value.optString("nickname"),
                            server = server,
                            region = accountRegion,
                            level = value.optInt("level", 0)
                        )
                    )
                }
            }
        }
    }

    suspend fun notes(cookie: String, role: Role): DailyNote {
        val endpoint = when (role.game) {
            Game.GENSHIN -> if (role.region == ApiRegion.CHINESE) {
                "https://api-takumi-record.mihoyo.com/game_record/app/genshin/api/dailyNote"
            } else "https://sg-public-api.hoyolab.com/event/game_record/genshin/api/dailyNote"
            Game.STAR_RAIL -> if (role.region == ApiRegion.CHINESE) {
                "https://api-takumi-record.mihoyo.com/game_record/app/hkrpg/api/note"
            } else "https://bbs-api-os.hoyolab.com/game_record/hkrpg/api/note"
            Game.ZZZ -> if (role.region == ApiRegion.CHINESE) {
                "https://api-takumi-record.mihoyo.com/event/game_record_zzz/api/zzz/note"
            } else "https://sg-act-public-api.hoyolab.com/event/game_record_zzz/api/zzz/note"
            Game.HONKAI -> if (role.region == ApiRegion.CHINESE) {
                "https://api-takumi-record.mihoyo.com/game_record/app/honkai3rd/api/note"
            } else "https://bbs-api-os.hoyolab.com/game_record/honkai3rd/api/note"
            Game.TOT -> throw UnsupportedOperationException("未定事件簿にはリアルタイムノートAPIがありません")
        }
        val data = fetch(
            endpoint,
            cookie,
            role.region,
            mapOf("role_id" to role.uid, "server" to role.server)
        )
        val note = when (role.game) {
            Game.GENSHIN -> {
                val tasks = data.optJSONObject("daily_task")
                val done = tasks?.let { daily ->
                    when {
                        daily.has("finished_num") -> daily.optInt("finished_num")
                        daily.has("completed_tasks") -> daily.optInt("completed_tasks")
                        else -> null
                    }
                } ?: data.int("finished_task_num", "completed_commissions")
                val total = (tasks?.let { daily ->
                    when {
                        daily.has("total_num") -> daily.optInt("total_num")
                        daily.has("max_tasks") -> daily.optInt("max_tasks")
                        else -> null
                    }
                } ?: data.int("total_task_num", "max_commissions")).takeIf { it > 0 } ?: 4
                val claimed = tasks?.let { daily ->
                    when {
                        daily.has("is_extra_task_reward_received") ->
                            daily.optBoolean("is_extra_task_reward_received")
                        daily.has("claimed_commission_reward") ->
                            daily.optBoolean("claimed_commission_reward")
                        else -> null
                    }
                } ?: (data.optBoolean("is_extra_task_reward_received") ||
                    data.optBoolean("claimed_commission_reward"))
                DailyNote(
                    Progress(data.int("current_resin"), data.int("max_resin")),
                    data.long("resin_recovery_time", "remaining_resin_recovery_time"),
                    Progress(done, total),
                    done >= total && claimed,
                    listOf(
                        Detail(
                            "洞天宝銭",
                            value(data, "current_home_coin", "current_realm_currency") + " / " +
                                value(data, "max_home_coin", "max_realm_currency")
                        ),
                        Detail(
                            "派遣",
                            data.int("current_expedition_num").toString() + " / " +
                                data.int("max_expedition_num", "max_expeditions")
                        )
                    )
                )
            }
            Game.STAR_RAIL -> {
                val train = data.int("current_train_score")
                val maxTrain = data.int("max_train_score")
                DailyNote(
                    Progress(data.int("current_stamina"), data.int("max_stamina")),
                    data.long("stamina_recover_time"),
                    Progress(train, maxTrain),
                    maxTrain > 0 && train >= maxTrain,
                    listOf(Detail("予備開拓力", data.int("current_reserve_stamina").toString()))
                )
            }
            Game.ZZZ -> {
                val battery = data.optJSONObject("battery_charge") ?: JSONObject()
                val engagement = data.optJSONObject("engagement") ?: JSONObject()
                val score = engagement.int("current")
                val maxScore = engagement.int("max")
                DailyNote(
                    Progress(battery.int("current"), battery.int("max")),
                    battery.long("seconds_till_full"),
                    Progress(score, maxScore),
                    maxScore > 0 && score >= maxScore,
                    listOf(
                        Detail("スクラッチ", if (data.optBoolean("scratch_card_completed")) "完了" else "未完了"),
                        Detail("ビデオ屋", data.optString("video_store_state").ifBlank { "—" })
                    )
                )
            }
            Game.HONKAI -> {
                val greedy = data.optJSONObject("greedy_endless") ?: JSONObject()
                val ultra = data.optJSONObject("ultra_endless") ?: JSONObject()
                val arena = data.optJSONObject("battle_field") ?: JSONObject()
                val godWar = data.optJSONObject("god_war") ?: JSONObject()
                DailyNote(
                    Progress(data.int("current_stamina"), data.int("max_stamina")),
                    data.long("stamina_recover_time"),
                    null,
                    null,
                    listOf(
                        Detail("訓練スコア", data.int("current_train_score").toString()),
                        Detail("古の楽園", greedy.int("cur_reward").toString() + " / " + greedy.int("max_reward")),
                        Detail("超弦空間", ultra.int("group_level").toString() + " · " + ultra.int("challenge_score")),
                        Detail("記憶戦場", arena.int("cur_reward").toString() + " / " + arena.int("max_reward")),
                        Detail("往世楽土", godWar.int("cur_reward").toString() + " / " + godWar.int("max_reward"))
                    )
                )
            }
            Game.TOT -> error("unsupported")
        }
        return note.copy(rawFields = flatten(data))
    }

    private fun flatten(value: JSONObject, prefix: String = ""): List<Detail> = buildList {
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            addAll(flattenValue(value.opt(key), path))
        }
    }

    private fun flattenValue(value: Any?, path: String): List<Detail> = when (value) {
        is JSONObject -> flatten(value, path)
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                addAll(flattenValue(value.opt(index), "$path[$index]"))
            }
        }
        null, JSONObject.NULL -> emptyList()
        else -> listOf(Detail(path, value.toString()))
    }

    private suspend fun fetch(
        baseUrl: String,
        cookie: String,
        region: ApiRegion,
        params: Map<String, String> = emptyMap()
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = ApiUrlComposer.compose(baseUrl, "", params)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Cookie", cookie)
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
            )
            setRequestProperty(
                "Referer",
                if (region == ApiRegion.CHINESE) "https://bbs.mihoyo.com/" else "https://www.hoyolab.com/"
            )
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-rpc-language", language)
            setRequestProperty("x-rpc-lang", language)
            setRequestProperty("x-rpc-client_type", "5")
            setRequestProperty("x-rpc-app_version", if (region == ApiRegion.CHINESE) "2.11.1" else "1.5.0")
            setRequestProperty("DS", dynamicSecret(region, params))
        }
        try {
            val status = connection.responseCode
            val payload = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw HoYoApiException("HTTP $status")
            val root = JSONObject(payload)
            mergeSetCookies(cookie, connection.headerFields)
            val code = root.optInt("retcode", root.optInt("code", 0))
            if (code != 0) {
                throw HoYoApiException(
                    root.optString("message").ifBlank { "API error $code" } + " ($code)"
                )
            }
            root.optJSONObject("data") ?: JSONObject()
        } finally {
            connection.disconnect()
        }
    }

    private fun mergeSetCookies(cookie: String, headers: Map<String?, List<String>>?) {
        val accepted = setOf(
            "ltuid", "ltoken", "ltuid_v2", "ltoken_v2", "ltmid_v2",
            "account_id", "account_id_v2", "account_mid_v2", "cookie_token",
            "cookie_token_v2", "stoken", "stoken_v2", "login_ticket",
            "login_ticket_token", "e_nap_token", "e_hkrpg_token", "e_lrsag"
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

    private fun dynamicSecret(region: ApiRegion, params: Map<String, String>): String {
        val timestamp = System.currentTimeMillis() / 1000
        val random = SecureRandom()
        val nonce: String
        val seed: String
        if (region == ApiRegion.OVERSEAS) {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            nonce = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
            seed = "salt=6s25p5ox5y14umn1p61aqyyvbvvl3lrt&t=$timestamp&r=$nonce"
        } else {
            nonce = (100001 + random.nextInt(100000)).toString()
            val query = params.toSortedMap().entries.joinToString("&") { it.key + "=" + it.value }
            seed = "salt=xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs&t=$timestamp&r=$nonce&b=&q=$query"
        }
        val hash = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$timestamp,$nonce,$hash"
    }

    private fun serverFor(game: Game, uid: String, region: ApiRegion): String {
        val prefix = uid.firstOrNull()
        return when (game) {
            Game.GENSHIN -> if (region == ApiRegion.CHINESE) {
                when (prefix) {
                    '5' -> "cn_qd01"
                    '1', '2', '3' -> "cn_gf01"
                    else -> ""
                }
            } else when (prefix) {
                '6' -> "os_usa"
                '7' -> "os_euro"
                '8', '1' -> "os_asia"
                '9' -> "os_cht"
                else -> ""
            }
            Game.STAR_RAIL -> if (region == ApiRegion.CHINESE) {
                when (prefix) {
                    '5' -> "prod_qd_cn"
                    '1', '2' -> "prod_gf_cn"
                    else -> ""
                }
            } else when (prefix) {
                '6' -> "prod_official_usa"
                '7' -> "prod_official_eur"
                '8' -> "prod_official_asia"
                '9' -> "prod_official_cht"
                else -> ""
            }
            Game.ZZZ -> if (region == ApiRegion.CHINESE) {
                if (uid.length == 8) "prod_gf_cn" else ""
            } else when {
                uid.startsWith("10") -> "prod_gf_us"
                uid.startsWith("13") -> "prod_gf_jp"
                uid.startsWith("15") -> "prod_gf_eu"
                uid.startsWith("17") -> "prod_gf_sg"
                else -> ""
            }
            Game.HONKAI -> if (region == ApiRegion.CHINESE) "cn01" else "overseas01"
            Game.TOT -> if (region == ApiRegion.CHINESE) "prod_gf_cn" else "prod_official"
        }
    }

    private fun value(o: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull {
        o.opt(it)?.takeUnless { raw -> raw == JSONObject.NULL }?.toString()
    } ?: "—"

    private fun JSONObject.int(vararg keys: String): Int = keys.firstNotNullOfOrNull {
        opt(it)?.toString()?.toIntOrNull()
    } ?: 0

    private fun JSONObject.long(vararg keys: String): Long? = keys.firstNotNullOfOrNull {
        opt(it)?.toString()?.toLongOrNull()
    }
}
