package com.atuy.hoyodashboard.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

class CookieSessionManager(
    private val onCookieUpdate: ((String) -> Unit)? = null
) {
    suspend fun refresh(cookie: String, region: ApiRegion): String? = withContext(Dispatchers.IO) {
        val current = parse(cookie)
        val updated = when {
            region == ApiRegion.OVERSEAS &&
                !current["stoken_v2"].isNullOrBlank() &&
                !current["ltmid_v2"].isNullOrBlank() -> refreshOverseasV2(current)
            region == ApiRegion.CHINESE &&
                !current["stoken_v2"].isNullOrBlank() &&
                !current["ltmid_v2"].isNullOrBlank() -> refreshChineseV2(current)
            region == ApiRegion.OVERSEAS &&
                !current["stoken"].isNullOrBlank() &&
                (!current["cookie_token"].isNullOrBlank() || !current["account_id"].isNullOrBlank()) ->
                refreshOverseasLegacy(current)
            else -> null
        } ?: return@withContext null

        val merged = current + updated
        val serialized = merged.entries.joinToString("; ") { it.key + "=" + it.value }
        if (serialized != cookie) onCookieUpdate?.invoke(serialized)
        serialized
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

    private fun refreshOverseasV2(cookies: Map<String, String>): Map<String, String>? {
        val requestCookies = cookies.toMutableMap()
        requestCookies["mid"] = cookies["ltmid_v2"].orEmpty()
        val body = JSONObject().put("dst_token_types", JSONArray().put(2).put(4))
        val headers = mapOf(
            "DS" to dynamicSecret(APP_LOGIN_SALT, body.toString(), includeBody = true),
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

    private fun refreshChineseV2(cookies: Map<String, String>): Map<String, String>? {
        val requestCookies = cookies.toMutableMap()
        requestCookies["mid"] = cookies["ltmid_v2"].orEmpty()
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

    private fun refreshOverseasLegacy(cookies: Map<String, String>): Map<String, String>? {
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

    private fun request(
        method: String,
        endpoint: String,
        cookies: Map<String, String>,
        headers: Map<String, String>,
        body: JSONObject?
    ): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cookie", cookies.entries.joinToString("; ") { it.key + "=" + it.value })
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
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
            if (status !in 200..299) throw HoYoApiException("Cookie refresh HTTP $status")
            return JSONObject(payload)
        } finally {
            connection.disconnect()
        }
    }

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

    private fun dynamicSecret(
        salt: String,
        body: String? = null,
        includeBody: Boolean = false
    ): String {
        val timestamp = System.currentTimeMillis() / 1000
        val random = SecureRandom()
        val alphabet = if (includeBody) {
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        } else "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val nonce = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
        val seed = if (includeBody) {
            "salt=$salt&t=$timestamp&r=$nonce&b=${body.orEmpty()}&q="
        } else "salt=$salt&t=$timestamp&r=$nonce"
        val hash = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$timestamp,$nonce,$hash"
    }

    companion object {
        private const val APP_LOGIN_SALT = "IZPgfb0dRPtBeLuFkdDznSZ6f4wWt6y2"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
    }
}
