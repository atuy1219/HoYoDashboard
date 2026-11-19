package com.atuy.hoyodashboard.api

import org.json.JSONObject
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class GameLoginChallenge internal constructor(val checkId: String, val parameters: JSONObject)
class GameLoginVerification(val checkId: String, val challenge: String, val validation: String)

sealed interface GameLoginStep {
    class Challenge internal constructor(val challenge: GameLoginChallenge) : GameLoginStep
    class Email internal constructor(internal val uid: String, internal val ticket: String) : GameLoginStep
    class Complete internal constructor(
        val openId: String,
        val comboId: String,
        val comboToken: String,
        val heartbeat: Boolean,
        val accountType: Int
    ) : GameLoginStep
}

class GameAccountAuth(
    game: ApiGame,
    private val region: ApiRegion,
    private val deviceId: String = UUID.randomUUID().toString(),
    private val transport: ApiTransport = ApiTransport()
) {
    private val mainland = region == ApiRegion.CHINESE
    private val route = when (game) {
        ApiGame.GENSHIN -> GameRoute("hk4e", "4", if (mainland) "d0d3a7342df2026a70f650b907800111" else "6a4c78fe0356ba4673b8071127b28123")
        ApiGame.STARRAIL -> GameRoute("hkrpg", if (mainland) "8" else "11", if (mainland) "4650f3a396d34d576c3d65df26415394" else "d74818dabd4182d4fbac7f8df1622648")
        ApiGame.HONKAI -> GameRoute("bh3", if (mainland) "1" else "8", if (mainland) "0ebc517adb1b62c6b408df153331f9aa" else "243187699ab762b682a2a2e50ba02285")
        ApiGame.ZZZ -> GameRoute("nap", if (mainland) "12" else "15", if (mainland) "4650f3a396d34d576c3d65df26415394" else "ff0f2776bf515d79d1f8ff1fb98b2a06")
        ApiGame.TOT -> throw IllegalArgumentException("未定事件簿のゲーム認証に対応していません")
    }
    private val gameBiz = route.slug + if (mainland) "_cn" else if (game == ApiGame.HONKAI) "_os" else "_global"
    private val sdk = when {
        mainland && game == ApiGame.HONKAI -> "https://api-sdk.mihoyo.com"
        mainland -> "https://${route.slug}-sdk.mihoyo.com"
        else -> "https://${route.slug}-sdk-os.hoyoverse.com"
    }
    private val platform = if (mainland) "https://gameapi-account.mihoyo.com" else "https://api-account-os.hoyoverse.com"
    private val headers = mapOf(
        "x-rpc-client_type" to "1", "x-rpc-channel_id" to "1", "x-rpc-device_id" to deviceId,
        "x-rpc-game_biz" to gameBiz
    )

    suspend fun login(
        account: String,
        password: String,
        proof: GameLoginVerification? = null,
        encrypted: Boolean = false
    ): GameLoginStep {
        require(account.isNotBlank() && password.isNotEmpty()) { "アカウントとパスワードを入力してください" }
        val risk = proof?.header() ?: run {
            val check = riskCheck("login", "/shield/api/login", account)
            check.challenge()?.let { return GameLoginStep.Challenge(it) }
            riskHeader(check.getString("id"))
        }
        val encryption = NativeAccountAuth(region, deviceId, transport)
        val body = JSONObject().put("account", account)
            .put("password", if (encrypted) password else encryption.encryptGamePassword(password))
            .put("is_crypto", true)
        val response = post(sdk, "$gameBiz/mdk/shield/api/login", body, headers + ("x-rpc-risky" to risk))
        val user = response.getJSONObject("account")
        val uid = user.getString("uid")
        return if (response.optBoolean("device_grant_required")) {
            GameLoginStep.Email(uid, user.getString("device_grant_ticket"))
        } else {
            finish(uid, user.getString("token"))
        }
    }

    suspend fun sendVerificationEmail(
        step: GameLoginStep.Email,
        deviceModel: String,
        deviceName: String,
        proof: GameLoginVerification? = null
    ): GameLoginChallenge? {
        val risk = proof?.header() ?: run {
            val check = riskCheck("device_grant", "/device/api/preGrantByTicket")
            check.challenge()?.let { return it }
            riskHeader(check.getString("id"))
        }
        val device = JSONObject().put("device_id", deviceId).put("device_model", deviceModel)
            .put("device_name", deviceName).put("client", 1)
        val body = JSONObject().put("way", "Way_Email").put("action_ticket", step.ticket).put("device", device)
        post(platform, "account/device/api/preGrantByTicket", body, headers + mapOf("x-rpc-risky" to risk, "x-rpc-language" to "en"))
        return null
    }

    suspend fun verifyEmail(step: GameLoginStep.Email, code: String): GameLoginStep.Complete {
        require(code.isNotBlank()) { "確認コードを入力してください" }
        val body = JSONObject().put("ticket", step.ticket).put("code", code)
        val response = post(platform, "account/device/api/grant", body, headers + ("x-rpc-language" to "en"))
        return finish(step.uid, response.getString("game_token"))
    }

    private suspend fun finish(uid: String, token: String): GameLoginStep.Complete {
        val data = """{"uid": ${JSONObject.quote(uid)}, "token": ${JSONObject.quote(token)}, "guest": false}"""
        val body = JSONObject().put("channel_id", 1).put("device", deviceId).put("app_id", route.id).put("data", data)
        val message = body.keys().asSequence().sorted().joinToString("&") { "$it=${body.get(it)}" }
        val hmac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(route.key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        body.put("sign", hmac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it.toInt() and 255)
        })
        val result = post(sdk, "$gameBiz/combo/granter/login/v2/login", body, headers)
        return GameLoginStep.Complete(
            result.getString("open_id"), result.getString("combo_id"), result.getString("combo_token"),
            result.optBoolean("heartbeat"), result.getInt("account_type")
        )
    }

    private suspend fun riskCheck(action: String, api: String, account: String? = null): JSONObject {
        val body = JSONObject().put("action_type", action).put("api_name", api)
        account?.let { body.put("username", it) }
        return post(platform, "account/risky/api/check", body, headers - "x-rpc-device_id")
    }

    private fun JSONObject.challenge(): GameLoginChallenge? =
        optJSONObject("geetest")?.let { GameLoginChallenge(getString("id"), it) }

    private fun GameLoginVerification.header() = riskHeader(checkId, challenge, validation)

    private fun riskHeader(id: String, challenge: String = "", validation: String = "") =
        "id=$id;c=$challenge;s=$validation|jordan;v=$validation"

    private suspend fun post(base: String, path: String, body: JSONObject, headers: Map<String, String>): JSONObject =
        transport.send(base, path, "", region, "ja-jp", "POST", emptyMap(), body, headers)

    private data class GameRoute(val slug: String, val id: String, val key: String)
}
