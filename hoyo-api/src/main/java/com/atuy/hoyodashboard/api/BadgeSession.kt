package com.atuy.hoyodashboard.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

class BadgeSession {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Pair<Long, String>>()

    suspend fun cookieFor(cookie: String, account: ApiAccount, language: String): String {
        require(account.game == ApiGame.ZZZ || account.game == ApiGame.STARRAIL)
        val baseCookie = stripBadgeCookies(cookie)
        val cookieHash = MessageDigest.getInstance("SHA-256")
            .digest(baseCookie.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val key = account.game.name + ":" + account.uid + ":" + cookieHash
        val now = System.currentTimeMillis()
        val tokens = mutex.withLock {
            cache[key]?.takeIf { it.first > now }?.second ?: login(baseCookie, account, language).also {
                cache[key] = (now + 36L * 60 * 60 * 1000) to it
            }
        }
        return if (tokens.isBlank()) baseCookie else "$baseCookie; $tokens"
    }

    suspend fun invalidate(account: ApiAccount) = mutex.withLock {
        val prefix = account.game.name + ":" + account.uid + ":"
        cache.keys.filter { it.startsWith(prefix) }.forEach(cache::remove)
    }

    private suspend fun login(cookie: String, account: ApiAccount, language: String): String =
        withContext(Dispatchers.IO) {
            val suffix = if (account.region == ApiRegion.CHINESE) "cn" else "global"
            val gameBiz = when (account.game) {
                ApiGame.ZZZ -> "nap_" + suffix
                ApiGame.STARRAIL -> "hkrpg_" + suffix
                else -> throw IllegalArgumentException("Badge login unsupported for the selected game")
            }
            val body = JSONObject()
                .put("game_biz", gameBiz)
                .put("lang", language)
                .put("region", account.server)
                .put("uid", account.uid)
            val url = if (account.region == ApiRegion.CHINESE) {
                "https://api-takumi.mihoyo.com/common/badge/v1/login/account"
            } else "https://sg-act-public-api.hoyolab.com/common/badge/v1/login/account"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Cookie", cookie)
                setRequestProperty(
                    "Referer",
                    if (account.region == ApiRegion.CHINESE) "https://bbs.mihoyo.com/" else "https://www.hoyolab.com/"
                )
                setRequestProperty("x-rpc-language", language)
                setRequestProperty("x-rpc-lang", language)
                if (account.region == ApiRegion.CHINESE) {
                    setRequestProperty("x-rpc-app_version", "2.11.1")
                    setRequestProperty("x-rpc-client_type", "5")
                    setRequestProperty("DS", dynamicSecret(body))
                }
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw HoYoApiException("Badge login HTTP $status")
                val parsed = JSONObject(response)
                if (parsed.optInt("retcode") != 0) {
                    throw HoYoApiException(
                        "Badge login: " + parsed.optString("message") +
                            " (" + parsed.optInt("retcode") + ")"
                    )
                }
                val accepted = setOf("e_nap_token", "e_hkrpg_token", "e_lrsag", "SERVERID", "SERVERCORSID")
                val setCookies = connection.headerFields
                    .filterKeys { it?.equals("set-cookie", ignoreCase = true) == true }
                    .values.flatten()
                    .map { it.substringBefore(';').trim() }
                    .filter { part -> part.substringBefore('=') in accepted && part.contains('=') }
                val name = if (account.game == ApiGame.ZZZ) "e_nap_token" else "e_hkrpg_token"
                if (setCookies.none { it.startsWith(name + "=") }) {
                    throw HoYoApiException("Badge token was not returned.")
                }
                setCookies.joinToString("; ")
            } finally {
                connection.disconnect()
            }
        }

    private fun stripBadgeCookies(cookie: String): String {
        val stale = setOf("e_nap_token", "e_hkrpg_token", "e_lrsag", "SERVERID", "SERVERCORSID")
        return cookie.split(';').mapNotNull { part ->
            val token = part.trim()
            val name = token.substringBefore('=').trim()
            token.takeIf { name.isNotBlank() && name !in stale }
        }.joinToString("; ")
    }

    private fun dynamicSecret(body: JSONObject): String {
        val timestamp = System.currentTimeMillis() / 1000
        val random = SecureRandom()
        val nonce = (100001 + random.nextInt(100000)).toString()
        val seed = "salt=xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs&t=$timestamp&r=$nonce&b=" +
            jsonForSignature(body) + "&q="
        val hash = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$timestamp,$nonce,$hash"
    }

    private fun jsonForSignature(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().joinToString(", ", "{", "}") { key ->
            JSONObject.quote(key) + ": " + jsonForSignature(value.opt(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(", ", "[", "]") {
            jsonForSignature(value.opt(it))
        }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
    }
}
