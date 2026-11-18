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
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class HoYoClient {
    suspend fun dashboard(cookie: String): List<DashboardItem> = coroutineScope {
        accounts(cookie).map { role ->
            async {
                try {
                    DashboardItem(role, notes(cookie, role), null)
                } catch (error: Exception) {
                    DashboardItem(role, null, error.message ?: "取得失敗")
                }
            }
        }.awaitAll()
    }

    suspend fun accounts(cookie: String): List<Role> {
        val data = fetch("https://api-os-takumi.mihoyo.com/binding/api/getUserGameRolesByCookie", cookie)
        val roles = data.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until roles.length()) {
                val value = roles.optJSONObject(i) ?: continue
                val game = when {
                    value.optString("game_biz").startsWith("hk4e_") -> Game.GENSHIN
                    value.optString("game_biz").startsWith("hkrpg_") -> Game.STAR_RAIL
                    value.optString("game_biz").startsWith("nap_") -> Game.ZZZ
                    else -> null
                } ?: continue
                val uid = value.optString("game_uid")
                val server = value.optString("region").ifBlank { serverFor(game, uid) }
                if (uid.isNotBlank() && server.isNotBlank() && none { it.game == game && it.uid == uid }) {
                    add(Role(game, uid, value.optString("nickname"), server))
                }
            }
        }
    }

    suspend fun notes(cookie: String, role: Role): DailyNote {
        val endpoint = when (role.game) {
            Game.GENSHIN -> "https://sg-public-api.hoyolab.com/event/game_record/genshin/api/dailyNote"
            Game.STAR_RAIL -> "https://bbs-api-os.hoyolab.com/game_record/hkrpg/api/note"
            Game.ZZZ -> "https://sg-act-public-api.hoyolab.com/event/game_record_zzz/api/zzz/note"
        }
        val url = endpoint + "?role_id=" + encode(role.uid) + "&server=" + encode(role.server)
        val data = fetch(url, cookie)
        val note = when (role.game) {
            Game.GENSHIN -> {
                val tasks = data.optJSONObject("daily_task")
                val done = tasks?.int("completed_tasks") ?: data.int("finished_task_num", "completed_commissions")
                val total = (tasks?.int("max_tasks") ?: data.int("total_task_num", "max_commissions")).takeIf { it > 0 } ?: 4
                val claimed = data.optBoolean("is_extra_task_reward_received") || data.optBoolean("claimed_commission_reward")
                DailyNote(
                    Progress(data.int("current_resin"), data.int("max_resin")),
                    data.long("resin_recovery_time", "remaining_resin_recovery_time"),
                    Progress(done, total),
                    done >= total && claimed,
                    listOf(
                        Detail("洞天宝銭", value(data, "current_home_coin", "current_realm_currency") + " / " + value(data, "max_home_coin", "max_realm_currency")),
                        Detail("派遣", data.int("current_expedition_num").toString() + " / " + data.int("max_expedition_num", "max_expeditions"))
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

    private suspend fun fetch(url: String, cookie: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("Cookie", cookie)
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
            setRequestProperty("Referer", "https://www.hoyolab.com/")
            setRequestProperty("x-rpc-language", "ja-jp")
            setRequestProperty("x-rpc-lang", "ja-jp")
            setRequestProperty("x-rpc-client_type", "5")
            setRequestProperty("x-rpc-app_version", "1.5.0")
            setRequestProperty("DS", dynamicSecret())
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw HoYoApiException("HTTP " + status)
            val root = JSONObject(payload)
            val code = root.optInt("retcode")
            if (code != 0) throw HoYoApiException(root.optString("message", "API error") + " (" + code + ")")
            root.optJSONObject("data") ?: JSONObject()
        } finally {
            connection.disconnect()
        }
    }

    private fun dynamicSecret(): String {
        val time = System.currentTimeMillis() / 1000
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val random = SecureRandom()
        val nonce = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
        val seed = "salt=6s25p5ox5y14umn1p61aqyyvbvvl3lrt&t=" + time + "&r=" + nonce
        val md5 = MessageDigest.getInstance("MD5").digest(seed.toByteArray())
        return time.toString() + "," + nonce + "," + md5.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun serverFor(game: Game, uid: String): String {
        return when (game) {
            Game.GENSHIN -> when (uid.firstOrNull()) {
                '6' -> "os_usa"
                '7' -> "os_euro"
                '8' -> "os_asia"
                '9' -> "os_cht"
                else -> ""
            }
            Game.STAR_RAIL -> when (uid.firstOrNull()) {
                '6' -> "prod_official_usa"
                '7' -> "prod_official_eur"
                '8' -> "prod_official_asia"
                '9' -> "prod_official_cht"
                else -> ""
            }
            Game.ZZZ -> when {
                uid.startsWith("10") -> "prod_gf_us"
                uid.startsWith("13") -> "prod_gf_jp"
                uid.startsWith("15") -> "prod_gf_eu"
                uid.startsWith("17") -> "prod_gf_sg"
                else -> ""
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
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
