package com.atuy.hoyodashboard.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest

class BadgeSession(private val transport: ApiTransport = ApiTransport()) {
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
        return listOf(baseCookie, tokens).filter(String::isNotBlank).joinToString("; ")
    }

    suspend fun invalidate(account: ApiAccount) = mutex.withLock {
        val prefix = account.game.name + ":" + account.uid + ":"
        cache.keys.filter { it.startsWith(prefix) }.forEach(cache::remove)
    }

    private suspend fun login(cookie: String, account: ApiAccount, language: String): String {
        val mainland = account.region == ApiRegion.CHINESE
        val game = if (account.game == ApiGame.ZZZ) "nap" else "hkrpg"
        val body = JSONObject().put("game_biz", game + if (mainland) "_cn" else "_global")
            .put("lang", language).put("region", account.server).put("uid", account.uid)
        val base = if (mainland) "https://api-takumi.mihoyo.com" else "https://sg-act-public-api.hoyolab.com"
        val referer = if (mainland) "https://bbs.mihoyo.com/" else "https://www.hoyolab.com/"
        val response = transport.exchange(
            base, "common/badge/v1/login/account", cookie, account.region, language, "POST",
            emptyMap(), body, mapOf("Referer" to referer), rpcHeaders = true, signed = mainland
        )
        response.requireSuccess()
        val accepted = setOf("e_nap_token", "e_hkrpg_token", "e_lrsag", "SERVERID", "SERVERCORSID")
        val tokens = response.headers.filterKeys { it.equals("set-cookie", ignoreCase = true) }
            .values.flatten().map { it.substringBefore(';').trim() }
            .filter { '=' in it && it.substringBefore('=') in accepted }
        val name = if (account.game == ApiGame.ZZZ) "e_nap_token" else "e_hkrpg_token"
        if (tokens.none { it.startsWith("$name=") && it.substringAfter('=').isNotBlank() }) {
            throw HoYoApiException("ゲーム記録の認証情報を取得できませんでした")
        }
        return tokens.joinToString("; ")
    }

    private fun stripBadgeCookies(cookie: String): String {
        val stale = setOf("e_nap_token", "e_hkrpg_token", "e_lrsag", "SERVERID", "SERVERCORSID")
        return cookie.split(';').mapNotNull { part ->
            val token = part.trim()
            val name = token.substringBefore('=').trim()
            token.takeIf { name.isNotBlank() && name !in stale }
        }.joinToString("; ")
    }

}
