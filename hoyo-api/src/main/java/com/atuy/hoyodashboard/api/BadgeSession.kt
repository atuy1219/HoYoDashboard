package com.atuy.hoyodashboard.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        val cookieHash = MessageDigest.getInstance("SHA-256")
            .digest(cookie.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val key = account.game.name + ":" + account.uid + ":" + cookieHash
        val now = System.currentTimeMillis()
        val tokens = mutex.withLock {
            cache[key]?.takeIf { it.first > now }?.second ?: login(cookie, account, language).also {
                cache[key] = (now + 12L * 60 * 60 * 1000) to it
            }
        }
        return cookie + "; " + tokens
    }

    suspend fun invalidate(account: ApiAccount) = mutex.withLock {
        val prefix = account.game.name + ":" + account.uid + ":"
        cache.keys.filter { it.startsWith(prefix) }.forEach(cache::remove)
    }

    private fun dynamicSecret(region: ApiRegion): String {
        val timestamp = System.currentTimeMillis() / 1000
        val random = SecureRandom()
        val nonce: String
        val seed: String
        if (region == ApiRegion.OVERSEAS) {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            nonce = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
            seed = "salt=6s25p5ox5y14umn1p61aqyyvbvvl3lrt&t=" + timestamp + "&r=" + nonce
        } else {
            nonce = (100001 + random.nextInt(100000)).toString()
            seed = "salt=xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs&t=" + timestamp + "&r=" + nonce +
                "&b=" + "{}" + "&q="
        }
        val hash = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return timestamp.toString() + "," + nonce + "," + hash
    }

    private suspend fun login(cookie: String, account: ApiAccount, language: String): String =
        withContext(Dispatchers.IO) {
            val suffix = if (account.region == ApiRegion.CHINESE) "cn" else "global"
            val gameBiz = when (account.game) {
                ApiGame.ZZZ -> "nap_" + suffix
                ApiGame.STARRAIL -> "hkrpg_" + suffix
                else -> throw IllegalArgumentException("Badge login unsupported for the selected game")
            }
            val base = if (account.region == ApiRegion.CHINESE) {
                "https://api-takumi.mihoyo.com/common/badge/v1/login/account"
            } else "https://sg-act-public-api.hoyolab.com/common/badge/v1/login/account"
            val body = JSONObject()
                .put("game_biz", gameBiz)
                .put("lang", language)
                .put("region", account.server)
                .put("uid", account.uid)
            val connection = (URL(
                base
            ).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Cookie", cookie)
                setRequestProperty("Referer", if (account.region == ApiRegion.CHINESE) "https://bbs.mihoyo.com/" else "https://www.hoyolab.com/")
                setRequestProperty("x-rpc-language", language)
                setRequestProperty("x-rpc-lang", language)
                setRequestProperty("x-rpc-app_version", if (account.region == ApiRegion.CHINESE) "2.11.1" else "1.5.0")
                setRequestProperty("x-rpc-client_type", "5")
                setRequestProperty("DS", dynamicSecret(account.region))
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) throw HoYoApiException("Badge login HTTP " + status)
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = JSONObject(response)
                if (parsed.optInt("retcode") != 0) {
                    throw HoYoApiException("Badge login: " + parsed.optString("message"))
                }
                val accepted = setOf(
                    "e_nap_token", "e_hkrpg_token", "e_lrsag", "SERVERID", "SERVERCORSID"
                )
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
}
