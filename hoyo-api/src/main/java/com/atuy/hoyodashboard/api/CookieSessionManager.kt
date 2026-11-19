package com.atuy.hoyodashboard.api

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

class CookieSessionManager internal constructor(
    private val onCookieUpdate: ((String) -> Unit)?,
    private val transport: ApiTransport
) {
    constructor(onCookieUpdate: ((String) -> Unit)? = null) : this(onCookieUpdate, ApiTransport())

    suspend fun refresh(cookie: String, region: ApiRegion): String? {
        val current = parse(cookie)
        val v2 = (!current["stoken"].isNullOrBlank() || !current["stoken_v2"].isNullOrBlank()) &&
            !current["ltmid_v2"].isNullOrBlank()
        val updated = when {
            v2 && region == ApiRegion.OVERSEAS -> refreshOverseasV2(current)
            v2 -> refreshChineseV2(current)
            region == ApiRegion.CHINESE &&
                !current["stoken"].isNullOrBlank() &&
                (!current["account_id"].isNullOrBlank() || !current["ltuid"].isNullOrBlank() ||
                    !current["stuid"].isNullOrBlank()) -> refreshChineseLegacy(current)
            region == ApiRegion.OVERSEAS &&
                !current["stoken"].isNullOrBlank() &&
                (!current["account_id"].isNullOrBlank() || !current["ltuid"].isNullOrBlank()) ->
                refreshOverseasLegacy(current)
            else -> null
        } ?: return null

        val merged = current + updated
        val serialized = merged.entries.joinToString("; ") { it.key + "=" + it.value }
        if (serialized != cookie) onCookieUpdate?.invoke(serialized)
        return serialized
    }

    suspend fun ensureGachaCookie(cookie: String, region: ApiRegion): String {
        fun normalized(values: Map<String, String>): Map<String, String> = values.toMutableMap().apply {
            putIfAbsent("account_id_v2", values["ltuid_v2"].orEmpty())
            putIfAbsent("account_mid_v2", values["ltmid_v2"].orEmpty())
        }.filterValues { it.isNotBlank() }
        fun serialize(values: Map<String, String>) =
            values.entries.joinToString("; ") { it.key + "=" + it.value }

        val current = normalized(parse(cookie))
        val normalizedCookie = serialize(current)
        if (!current["cookie_token_v2"].isNullOrBlank() &&
            !current["account_id_v2"].isNullOrBlank() &&
            !current["account_mid_v2"].isNullOrBlank()
        ) {
            if (normalizedCookie != cookie) onCookieUpdate?.invoke(normalizedCookie)
            return normalizedCookie
        }
        val refreshed = refresh(normalizedCookie, region) ?: normalizedCookie
        val result = normalized(parse(refreshed))
        require(
            !result["cookie_token_v2"].isNullOrBlank() &&
                !result["account_id_v2"].isNullOrBlank() &&
                !result["account_mid_v2"].isNullOrBlank()
        ) {
            "Authkey generation requires cookie_token_v2, account_id_v2 and account_mid_v2. Re-link HoYoLAB."
        }
        return refreshed
    }

    private suspend fun refreshOverseasV2(cookies: Map<String, String>): Map<String, String>? {
        val requestCookies = cookies.toMutableMap()
        requestCookies.putIfAbsent("stoken", cookies["stoken_v2"].orEmpty())
        requestCookies["mid"] = cookies["ltmid_v2"].orEmpty()
        val body = JSONObject().put("dst_token_types", JSONArray().put(2).put(4))
        val headers = mapOf(
            "DS" to dynamicSecret(APP_LOGIN_SALT),
            "x-rpc-app_id" to "c9oqaq3s3gu8"
        )
        val response = request(
            "POST",
            "https://sg-public-api.hoyoverse.com/account/ma-passport/token/getBySToken",
            requestCookies,
            headers,
            body
        )
        if (response.optInt("retcode") != 0) return null
        val tokens = response.optJSONObject("data")?.optJSONArray("tokens") ?: return null
        val result = linkedMapOf<String, String>()
        for (i in 0 until tokens.length()) {
            val token = tokens.optJSONObject(i) ?: continue
            when (token.optInt("token_type")) {
                2 -> result["ltoken_v2"] = token.optString("token")
                4 -> result["cookie_token_v2"] = token.optString("token")
            }
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private suspend fun refreshChineseV2(cookies: Map<String, String>): Map<String, String>? {
        val requestCookies = cookies.toMutableMap()
        requestCookies.putIfAbsent("stoken", cookies["stoken_v2"].orEmpty())
        val headers = mapOf(
            "DS" to dynamicSecret(APP_LOGIN_SALT),
            "x-rpc-app_id" to "bll8iq97cem8"
        )
        val response = request(
            "GET",
            "https://passport-api.mihoyo.com/account/auth/api/getCookieAccountInfoBySToken",
            requestCookies,
            headers,
            null
        )
        if (response.optInt("retcode") != 0) return null
        val data = response.optJSONObject("data") ?: return null
        val uid = data.optString("uid")
        val token = data.optString("cookie_token")
        if (uid.isBlank() || token.isBlank()) return null
        return mapOf("account_id" to uid, "cookie_token" to token)
    }

    private suspend fun refreshChineseLegacy(cookies: Map<String, String>): Map<String, String>? {
        val uid = cookies["account_id"] ?: cookies["ltuid"] ?: cookies["stuid"] ?: return null
        val endpoint = "https://api-takumi.mihoyo.com/auth/api/getCookieAccountInfoBySToken" +
            "?uid=" + java.net.URLEncoder.encode(uid, "UTF-8")
        val response = request("GET", endpoint, cookies, emptyMap(), null)
        if (response.optInt("retcode") != 0) return null
        val data = response.optJSONObject("data") ?: return null
        val result = linkedMapOf<String, String>()
        data.optString("uid").takeIf { it.isNotBlank() }?.let { result["account_id"] = it }
        data.optString("cookie_token").takeIf { it.isNotBlank() }?.let { result["cookie_token"] = it }
        return result.takeIf { it.isNotEmpty() }
    }

    private suspend fun refreshOverseasLegacy(cookies: Map<String, String>): Map<String, String>? {
        val uid = cookies["account_id"] ?: cookies["ltuid"] ?: return null
        val body = JSONObject(cookies).apply {
            remove("ltuid")
            remove("account_id")
            put("uid", uid)
        }
        val response = request(
            "POST",
            "https://api-account-os.hoyolab.com/account/auth/api/getCookieAccountInfoBySToken",
            emptyMap(),
            emptyMap(),
            body
        )
        if (response.optInt("retcode") != 0) return null
        val data = response.optJSONObject("data") ?: return null
        val result = linkedMapOf<String, String>()
        data.optString("uid").takeIf { it.isNotBlank() }?.let { result["account_id"] = it }
        data.optString("cookie_token").takeIf { it.isNotBlank() }?.let { result["cookie_token"] = it }
        return result.takeIf { it.isNotEmpty() }
    }

    private suspend fun request(
        method: String,
        endpoint: String,
        cookies: Map<String, String>,
        headers: Map<String, String>,
        body: JSONObject?
    ): JSONObject = transport.exchange(endpoint, "", serialize(cookies), ApiRegion.OVERSEAS, "ja-jp",
        method, emptyMap(), body, headers).payload

    private fun serialize(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

    private fun parse(cookie: String): Map<String, String> = buildMap {
        cookie.split(';').forEach { part ->
            val index = part.indexOf('=')
            if (index > 0) {
                val name = part.substring(0, index).trim()
                val value = part.substring(index + 1).trim()
                if (name.isNotBlank() && value.isNotBlank()) put(name, value)
            }
        }
    }

    private fun dynamicSecret(salt: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val random = SecureRandom()
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val nonce = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
        val seed = "salt=$salt&t=$timestamp&r=$nonce"
        val hash = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$timestamp,$nonce,$hash"
    }

    companion object {
        private const val APP_LOGIN_SALT = "IZPgfb0dRPtBeLuFkdDznSZ6f4wWt6y2"
    }
}
