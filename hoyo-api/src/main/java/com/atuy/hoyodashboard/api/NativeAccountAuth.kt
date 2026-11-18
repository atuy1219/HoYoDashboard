package com.atuy.hoyodashboard.api

import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher

sealed interface AccountLoginStep {
    class Complete internal constructor(private val cookies: Map<String, String>) : AccountLoginStep {
        fun cookieHeader(): String = cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }
    class VerifyChallenge internal constructor(val challenge: JSONObject) : AccountLoginStep {
        fun verificationHeader(proof: JSONObject): String {
            require(proof.length() > 0)
            val sessionId = challenge.getString("session_id")
            return "$sessionId;${Base64.getEncoder().encodeToString(proof.toString().toByteArray(Charsets.UTF_8))}"
        }
    }
    class VerifyEmail internal constructor(internal val ticket: String, internal val header: String) : AccountLoginStep
}

class QrLogin internal constructor(internal val ticket: String, val url: String)
enum class QrLoginStatus { CREATED, SCANNED, CONFIRMED }
class QrLoginUpdate internal constructor(val status: QrLoginStatus, val login: AccountLoginStep.Complete?)

class NativeAccountAuth(
    private val region: ApiRegion,
    private val deviceId: String = UUID.randomUUID().toString(),
    private val transport: ApiTransport = ApiTransport()
) {
    suspend fun loginWithPassword(
        account: String,
        password: String,
        verificationHeader: String? = null,
        encrypted: Boolean = false,
        tokenType: Int? = 6
    ): AccountLoginStep {
        val overseas = region == ApiRegion.OVERSEAS
        val body = credentials(account, password, if (overseas) overseasKey else mainlandKey, encrypted)
        val headers = if (overseas) baseHeaders(overseasApp, "4") + mapOf(
            "Origin" to "https://account.hoyolab.com",
            "Referer" to "https://account.hoyolab.com/"
        ) else mainlandHeaders()
        if (overseas) body.put("token_type", tokenType ?: JSONObject.NULL)
        val response = post(
            if (overseas) overseasWeb else mainland,
            if (overseas) "account/ma-passport/api/webLoginByPassword" else "account/ma-cn-passport/web/loginByPassword",
            body, headers, verificationHeader
        )
        response.challenge()?.let { return it }
        response.requireSuccess()
        val cookies = response.cookies().toMutableMap()
        response.payload.optJSONObject("data")?.optString("stoken")?.takeIf(String::isNotBlank)
            ?.let { cookies["stoken"] = it }
        return complete(cookies)
    }

    suspend fun loginWithAppPassword(
        account: String,
        password: String,
        verificationHeader: String? = null,
        email: AccountLoginStep.VerifyEmail? = null,
        encrypted: Boolean = false
    ): AccountLoginStep {
        require(region == ApiRegion.OVERSEAS) { "HoYoLABのアカウントを選択してください" }
        val body = credentials(account, password, overseasKey, encrypted)
        val headers = baseHeaders(overseasApp, "2").toMutableMap()
        headers["x-rpc-aigis_v4"] = "true"
        headers["x-rpc-app_version"] = "4.8.0"
        headers["x-rpc-sdk_version"] = "2.2.0"
        headers["DS"] = signature(
            "IZPgfb0dRPtBeLuFkdDznSZ6f4wWt6y2",
            nonce("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"),
            "&b=${body.toString().replace("\\/", "/")}&q="
        )
        email?.let { headers["x-rpc-verify"] = it.header }
        val response = post(overseasPassport, "account/ma-passport/api/appLoginByPassword", body, headers, verificationHeader)
        response.challenge()?.let { return it }
        if (response.code == -3239) {
            val header = checkNotNull(response.header("x-rpc-verify")) { "メール認証情報を取得できませんでした" }
            val envelope = JSONObject(header)
            val verify = envelope.get("verify_str")
            val detail = if (verify is JSONObject) verify else JSONObject(verify.toString())
            envelope.put("verify_str", detail.toString())
            return AccountLoginStep.VerifyEmail(detail.getString("ticket"), envelope.toString())
        }
        response.requireSuccess()
        val data = response.payload.getJSONObject("data")
        val user = data.getJSONObject("user_info")
        val uid = user.getString("aid")
        val mid = user.getString("mid")
        return complete(mapOf(
            "stoken" to data.getJSONObject("token").getString("token"),
            "ltuid_v2" to uid, "ltmid_v2" to mid, "account_id_v2" to uid, "account_mid_v2" to mid
        ))
    }

    suspend fun verifyEmail(ticket: AccountLoginStep.VerifyEmail, code: String) {
        require(region == ApiRegion.OVERSEAS && code.isNotBlank())
        val body = emailBody(ticket).put("email_captcha", code).put("verify_method", 2)
        post(overseasPassport, "account/ma-verifier/api/verifyActionTicketPartly", body, baseHeaders(overseasApp, "2"))
            .requireSuccess()
    }

    suspend fun resendVerificationEmail(
        ticket: AccountLoginStep.VerifyEmail,
        verificationHeader: String? = null
    ): AccountLoginStep.VerifyChallenge? {
        require(region == ApiRegion.OVERSEAS)
        val response = post(overseasPassport, "account/ma-verifier/api/createEmailCaptchaByActionTicket",
            emailBody(ticket), baseHeaders(overseasApp, "2"), verificationHeader)
        return response.challenge() ?: run { response.requireSuccess(); null }
    }

    suspend fun sendMobileCode(mobile: String, verificationHeader: String? = null): AccountLoginStep.VerifyChallenge? {
        require(region == ApiRegion.CHINESE)
        val response = post(mainland, "account/ma-cn-verifier/verifier/createLoginCaptcha",
            mobileBody(mobile), mainlandHeaders(), verificationHeader)
        return response.challenge() ?: run { response.requireSuccess(); null }
    }

    suspend fun loginWithMobileCode(mobile: String, code: String): AccountLoginStep.Complete {
        require(region == ApiRegion.CHINESE && code.isNotBlank())
        val response = post(mainland, "account/ma-cn-passport/web/loginByMobileCaptcha",
            mobileBody(mobile).put("captcha", code), mainlandHeaders())
        response.requireSuccess()
        return complete(response.cookies())
    }

    suspend fun checkMobileNumberValidity(mobile: String): Boolean {
        require(mobile.matches(Regex("[0-9]{11}"))) { "中国本土の携帯電話番号を入力してください" }
        val response = transport.exchange(
            "https://webapi.account.mihoyo.com", "Api/is_mobile_registrable", "", region, "ja-jp", "GET",
            mapOf("mobile" to mobile), null
        )
        response.requireSuccess()
        val data = response.payload.getJSONObject("data")
        return data.get("status").toString() != data.get("is_registable").toString()
    }

    suspend fun createMmt(cookie: String, game: ApiGame): JSONObject {
        val overseas = region == ApiRegion.OVERSEAS
        val params = if (overseas) mapOf("is_high" to "true", "app_key" to game.challengeKey())
        else mapOf("is_high" to "false")
        val response = transport.exchange(
            if (overseas) overseasWeb else "https://api-takumi-record.mihoyo.com",
            if (overseas) "event/toolcomsrv/risk/createGeetest" else "game_record/app/card/wapi/createVerification",
            cookie, region, "ja-jp", "GET", params, null, recordChallengeHeaders()
        )
        response.requireSuccess()
        return response.payload.getJSONObject("data")
    }

    suspend fun verifyMmt(cookie: String, game: ApiGame, challenge: String, validate: String, seccode: String) {
        require(region == ApiRegion.OVERSEAS) { "HoYoLABのアカウントを選択してください" }
        val body = JSONObject().put("geetest_challenge", challenge).put("geetest_validate", validate)
            .put("geetest_seccode", seccode).put("app_key", game.challengeKey())
        transport.exchange(overseasWeb, "event/toolcomsrv/risk/verifyGeetest", cookie, region, "ja-jp",
            "POST", emptyMap(), body, recordChallengeHeaders()).requireSuccess()
    }

    suspend fun generateFingerprint(board: String, advertisingId: String): String {
        val mainland = region == ApiRegion.CHINESE
        val random = SecureRandom()
        val seed = buildString { repeat(13) { append("0123456789abcdef"[random.nextInt(16)]) } }
        val body = JSONObject().put("device_id", deviceId).put("device_fp", seed)
            .put("seed_id", UUID.randomUUID().toString()).put("seed_time", System.currentTimeMillis().toString())
            .put("platform", "2").put("app_name", if (mainland) "bbs_cn" else "bbs_oversea")
            .put("ext_fields", JSONObject().put(if (mainland) "oaid" else "adid", advertisingId).put("board", board).toString())
            .put(if (mainland) "bbs_device_id" else "hoyolab_device_id", UUID.randomUUID().toString())
        val base = if (mainland) "https://public-data-api.mihoyo.com" else "https://sg-public-data-api.hoyoverse.com"
        val response = post(base, "device-fp/api/getFp", body, emptyMap())
        response.requireSuccess()
        val data = response.payload.getJSONObject("data")
        if (data.optInt("code") != 200) throw HoYoApiException(data.optString("msg", "通信エラー"))
        return data.getString("device_fp")
    }

    suspend fun createQrLogin(): QrLogin {
        require(region == ApiRegion.CHINESE)
        val response = post(mainland, "account/ma-cn-passport/web/createQRLogin", null, qrHeaders())
        response.requireSuccess()
        val data = response.payload.getJSONObject("data")
        return QrLogin(data.getString("ticket"), data.getString("url"))
    }

    suspend fun checkQrLogin(qr: QrLogin): QrLoginUpdate {
        require(region == ApiRegion.CHINESE)
        val response = post(mainland, "account/ma-cn-passport/web/queryQRLoginStatus",
            JSONObject().put("ticket", qr.ticket), qrHeaders())
        response.requireSuccess()
        return when (response.payload.getJSONObject("data").getString("status")) {
            "Created" -> QrLoginUpdate(QrLoginStatus.CREATED, null)
            "Scanned" -> QrLoginUpdate(QrLoginStatus.SCANNED, null)
            "Confirmed" -> QrLoginUpdate(QrLoginStatus.CONFIRMED, complete(response.cookies()))
            else -> throw HoYoApiException("QRコードの有効期限が切れました")
        }
    }

    private suspend fun post(
        base: String,
        path: String,
        body: JSONObject?,
        headers: Map<String, String>,
        verificationHeader: String? = null
    ): ApiExchange = transport.exchange(
        base, path, "", region, "ja-jp", "POST", emptyMap(), body,
        if (verificationHeader == null) headers else headers + ("x-rpc-aigis" to verificationHeader)
    )

    private fun baseHeaders(appId: String, clientType: String) = mapOf(
        "x-rpc-app_id" to appId, "x-rpc-client_type" to clientType, "x-rpc-device_id" to deviceId
    )

    private fun mainlandHeaders() = baseHeaders(mainlandApp, "4") + mapOf(
        "x-rpc-source" to "v2.webLogin",
        "x-rpc-device_fp" to "38d7fff8fd68c",
        "x-rpc-device_model" to "Firefox%20131.0",
        "x-rpc-device_name" to "Firefox",
        "x-rpc-game_biz" to "bbs_cn",
        "x-rpc-sdk_version" to "2.31.0",
        "DS" to mainlandSignature()
    )

    private fun qrHeaders() = baseHeaders(mainlandApp, "4") + mapOf(
        "x-rpc-game_biz" to "bbs_cn", "x-rpc-device_fp" to "38d7fa104e5d7"
    )

    private fun ApiExchange.challenge(): AccountLoginStep.VerifyChallenge? {
        if (code !in setOf(-3101, -3102)) return null
        return header("x-rpc-aigis")?.let { AccountLoginStep.VerifyChallenge(JSONObject(it)) }
    }

    private fun ApiExchange.cookies(): Map<String, String> =
        headers.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }
            .mapNotNull { cookie ->
                val part = cookie.substringBefore(';')
                val key = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (key in sessionCookies && value.isNotBlank()) key to value else null
            }.toMap()

    private fun complete(cookies: Map<String, String>): AccountLoginStep.Complete {
        if (cookies.isEmpty()) throw HoYoApiException("ログイン済みのセッションを取得できませんでした")
        return AccountLoginStep.Complete(cookies)
    }

    private fun emailBody(ticket: AccountLoginStep.VerifyEmail) =
        JSONObject().put("action_type", "verify_for_component").put("action_ticket", ticket.ticket)

    private fun mobileBody(mobile: String): JSONObject {
        require(mobile.matches(Regex("[0-9]{11}"))) { "中国本土の携帯電話番号を入力してください" }
        return JSONObject().put("mobile", encrypt(mobile, mainlandKey)).put("area_code", encrypt("+86", mainlandKey))
    }

    private fun credentials(account: String, password: String, key: String, encrypted: Boolean): JSONObject {
        require(account.isNotBlank() && password.isNotEmpty()) { "アカウントとパスワードを入力してください" }
        return JSONObject().put("account", if (encrypted) account else encrypt(account, key))
            .put("password", if (encrypted) password else encrypt(password, key))
    }

    internal fun encryptGamePassword(value: String): String = encrypt(value, mainlandKey)

    private fun encrypt(value: String, key: String): String {
        val spec = X509EncodedKeySpec(Base64.getDecoder().decode(key))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun recordChallengeHeaders(): Map<String, String> {
        val overseas = region == ApiRegion.OVERSEAS
        val salt = if (overseas) "6s25p5ox5y14umn1p61aqyyvbvvl3lrt" else "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs"
        val common = mapOf(
            "DS" to signature(salt, (100000 + SecureRandom().nextInt(100001)).toString(), "&b=&q=is_high=false"),
            "x-rpc-challenge_game" to "6", "x-rpc-client_type" to "5"
        )
        return if (overseas) common + mapOf(
            "x-rpc-app_version" to "2.55.0",
            "x-rpc-challenge_path" to "https://bbs-api-os.hoyolab.com/game_record/app/hkrpg/api/challenge"
        ) else common + mapOf(
            "x-rpc-app_version" to "2.60.1",
            "x-rpc-page" to "v1.4.1-rpg_#/rpg", "x-rpc-tool-version" to "v1.4.1-rpg"
        )
    }

    private fun ApiGame.challengeKey(): String = when (this) {
        ApiGame.GENSHIN -> "genshin_game_record"
        ApiGame.STARRAIL -> "hkrpg_game_record"
        ApiGame.HONKAI -> "bh3_game_record"
        ApiGame.ZZZ -> "nap_game_record"
        ApiGame.TOT -> "nxx_game_record"
    }

    private fun mainlandSignature() =
        signature("LyD1rXqMv2GJhnwdvCBjFOKGiKuLY3aO", nonce("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"))

    private fun nonce(alphabet: String): String {
        val random = SecureRandom()
        return buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun signature(salt: String, nonce: String, suffix: String = ""): String {
        val timestamp = System.currentTimeMillis() / 1000
        val input = "salt=$salt&t=$timestamp&r=$nonce$suffix"
        val hash = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).hex()
        return "$timestamp,$nonce,$hash"
    }

    private companion object {
        const val overseasApp = "c9oqaq3s3gu8"
        const val mainlandApp = "bll8iq97cem8"
        const val overseasWeb = "https://sg-public-api.hoyolab.com"
        const val overseasPassport = "https://sg-public-api.hoyoverse.com"
        const val mainland = "https://passport-api.miyoushe.com"

        const val overseasKey = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA4PMS2JVMwBsOIrYWRluY" +
            "wEiFZL7Aphtm9z5Eu/anzJ09nB00uhW+ScrDWFECPwpQto/GlOJYCUwVM/raQpAj" +
            "/xvcjK5tNVzzK94mhk+j9RiQ+aWHaTXmOgurhxSp3YbwlRDvOgcq5yPiTz0+kSeK" +
            "ZJcGeJ95bvJ+hJ/UMP0Zx2qB5PElZmiKvfiNqVUk8A8oxLJdBB5eCpqWV6CUqDKQ" +
            "KSQP4sM0mZvQ1Sr4UcACVcYgYnCbTZMWhJTWkrNXqI8TMomekgny3y+d6NX/cFa6" +
            "6jozFIF4HCX5aW8bp8C8vq2tFvFbleQ/Q3CU56EWWKMrOcpmFtRmC18s9biZBVR/" +
            "8QIDAQAB"
        const val mainlandKey = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDDvekdPMHN3AYhm/vktJT+YJr7" +
            "cI5DcsNKqdsx5DZX0gDuWFuIjzdwButrIYPNmRJ1G8ybDIF7oDW2eEpm5sMbL9zs" +
            "9ExXCdvqrn51qELbqj0XxtMTIpaCHFSI50PfPpTFV9Xt/hmyVwokoOXFlAEgCn+Q" +
            "CgGs52bFoYMtyi+xEQIDAQAB"

        val sessionCookies = setOf(
            "stoken", "stoken_v2", "stuid", "ltoken", "ltoken_v2", "ltuid", "ltuid_v2", "ltmid_v2",
            "account_id", "account_id_v2", "account_mid_v2", "cookie_token", "cookie_token_v2"
        )
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
