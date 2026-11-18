package com.atuy.hoyodashboard.api

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest
import java.security.SecureRandom

data class UpstreamActionDefinition(
    val name: String,
    val title: String,
    val required: List<String>,
    val confirmationRequired: Boolean = false
)

class UpstreamActions(
    private val language: String = "ja-jp",
    private val transport: ApiTransport = ApiTransport(),
    private val api: UpstreamApiClient = UpstreamApiClient(language, transport = transport)
) {

    fun availableActions(account: ApiAccount): List<UpstreamActionDefinition> {
        val actions = listOf(
            UpstreamActionDefinition("claim_daily_reward", "デイリーチェックイン", emptyList()),
            UpstreamActionDefinition("redeem_code", "交換コード", listOf("code")),
            UpstreamActionDefinition("update_settings", "プロフィール公開設定", listOf("setting_id", "public"), true),
            UpstreamActionDefinition("set_top_genshin_characters", "原神の注目キャラクター", listOf("character_ids")),
            UpstreamActionDefinition("set_calculator_sync", "育成計算の所持データ連携", listOf("public"), true),
            UpstreamActionDefinition("finish_mimo_task", "Mimoタスク完了", listOf("task_id")),
            UpstreamActionDefinition("claim_mimo_task_reward", "Mimo報酬受け取り", listOf("task_id")),
            UpstreamActionDefinition("buy_mimo_shop_item", "Mimoショップ交換", listOf("item_id"), true),
            UpstreamActionDefinition("draw_mimo_lottery", "Mimo抽選", emptyList(), true),
            UpstreamActionDefinition("reply_to_post", "HoYoLABに返信", listOf("post_id", "content"), true),
            UpstreamActionDefinition("delete_reply", "HoYoLAB返信削除", listOf("reply_id", "post_id"), true),
            UpstreamActionDefinition("join_topic", "HoYoLABトピック参加", listOf("topic_id")),
            UpstreamActionDefinition("leave_topic", "HoYoLABトピック退出", listOf("topic_id"), true),
            UpstreamActionDefinition("accompany_character", "コンパニオン設定", listOf("role_id", "topic_id")),
            UpstreamActionDefinition("browse_accompany_post", "コンパニオン投稿閲覧", listOf("post_id", "topic_id")),
            UpstreamActionDefinition(
                "set_accompany_voice_setting", "コンパニオン音声設定",
                listOf("game_id", "role_id", "voice_language", "script_language")
            )
        )
        return actions.filter { action ->
            when (action.name) {
                "redeem_code" -> account.region == ApiRegion.OVERSEAS && account.game != ApiGame.HONKAI
                "update_settings" -> account.game != ApiGame.TOT
                "set_top_genshin_characters", "set_calculator_sync" -> account.game == ApiGame.GENSHIN
                "finish_mimo_task", "claim_mimo_task_reward", "buy_mimo_shop_item", "draw_mimo_lottery",
                "accompany_character", "browse_accompany_post", "set_accompany_voice_setting" ->
                    account.region == ApiRegion.OVERSEAS
                else -> true
            }
        }
    }

    suspend fun execute(
        cookie: String,
        account: ApiAccount,
        name: String,
        arguments: Map<String, String>
    ): JSONObject {
        if (name == "check_in_community") throw UnsupportedOperationException("HoYoLABコミュニティのチェックインは終了しました")
        val operation = if (name == "browse_accompany_character_post") "browse_accompany_post" else name
        fun required(key: String): String = arguments[key]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(key + " is required")
        fun long(key: String): Long = required(key).toLongOrNull()
            ?: throw IllegalArgumentException(key + " must be an integer")
        fun int(key: String): Int = required(key).toIntOrNull()
            ?: throw IllegalArgumentException(key + " must be an integer")
        fun bool(key: String, default: Boolean = false): Boolean =
            arguments[key]?.let {
                when (it.lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> throw IllegalArgumentException(key + " must be true or false")
                }
            } ?: default

        require(availableActions(account).any { it.name == operation }) {
            "Action is not available for this account: " + name
        }
        val mimoGame = if (operation in setOf("finish_mimo_task", "claim_mimo_task_reward", "buy_mimo_shop_item", "draw_mimo_lottery") &&
            (arguments["game_id"].isNullOrBlank() || arguments["version_id"].isNullOrBlank())
        ) api.mimoGame(cookie, account, arguments["game_id"]?.toIntOrNull()) else null
        fun mimoId(key: String): Int = mimoGame?.getInt(key) ?: int(key)
        return when (operation) {
            "claim_daily_reward" -> claimDailyReward(
                cookie, account,
                arguments.filterKeys { it in setOf("geetest_challenge", "geetest_validate", "geetest_seccode") }
            )
            "set_calculator_sync" -> setCalculatorSync(cookie, account, bool("public"))
            "redeem_code" -> redeemCode(cookie, account, required("code"))
            "update_settings" -> updateSettings(cookie, account, int("setting_id"), bool("public"))
            "set_top_genshin_characters" -> setTopGenshinCharacters(
                cookie, account,
                required("character_ids").split(',').map { it.trim().toIntOrNull()
                    ?: throw IllegalArgumentException("character_ids must be comma-separated integers") }
            )
            "finish_mimo_task" -> finishMimoTask(cookie, account, long("task_id"), mimoId("game_id"), mimoId("version_id"))
            "claim_mimo_task_reward" -> claimMimoTaskReward(cookie, account, long("task_id"), mimoId("game_id"), mimoId("version_id"))
            "buy_mimo_shop_item" -> buyMimoShopItem(cookie, account, long("item_id"), mimoId("game_id"), mimoId("version_id"))
            "draw_mimo_lottery" -> drawMimoLottery(cookie, account, mimoId("game_id"), mimoId("version_id"))
            "reply_to_post" -> replyToPost(cookie, account, long("post_id"), required("content"))
            "delete_reply" -> deleteReply(cookie, account, long("reply_id"), long("post_id"))
            "join_topic" -> joinTopic(cookie, account, long("topic_id"))
            "leave_topic" -> leaveTopic(cookie, account, long("topic_id"))
            "accompany_character" -> accompanyCharacter(cookie, account, long("role_id"), long("topic_id"))
            "browse_accompany_post" -> browseAccompanyPost(cookie, account, long("post_id"), long("topic_id"))
            "set_accompany_voice_setting" -> setAccompanyVoiceSetting(
                cookie, account, int("game_id"), long("role_id"),
                arguments["voice_lang"] ?: required("voice_language"),
                arguments["script_lang"] ?: required("script_language"), bool("sync_all_roles")
            )
            else -> throw IllegalArgumentException("Unknown action: " + name)
        }
    }

    suspend fun claimDailyReward(
        cookie: String,
        account: ApiAccount,
        challenge: Map<String, String> = emptyMap()
    ): JSONObject {
        val url = rewardUrl(account)
        val headers = linkedMapOf("x-rpc-signgame" to gameShortName(account.game))
        if (account.region == ApiRegion.OVERSEAS) {
            headers["Referer"] = "https://act.hoyolab.com/"
        } else {
            headers["DS"] = simpleDynamicSecret("LyD1rXqMv2GJhnwdvCBjFOKGiKuLY3aO")
            headers["x-rpc-app_version"] = "2.70.1"
            headers["x-rpc-client_type"] = "5"
            headers["x-rpc-device_id"] = UUID.randomUUID().toString()
            headers["x-rpc-sys_version"] = "12"
            headers["x-rpc-platform"] = "android"
            headers["x-rpc-channel"] = "miyousheluodi"
            headers["x-rpc-device_model"] = cookieValue(cookie, "ltuid_v2", "ltuid", "account_id").orEmpty()
        }
        challenge.forEach { (key, value) ->
            headers[when (key) {
                "geetest_challenge" -> "x-rpc-challenge"
                "geetest_validate" -> "x-rpc-validate"
                "geetest_seccode" -> "x-rpc-seccode"
                else -> throw IllegalArgumentException("Unsupported challenge key.")
            }] = value
        }
        val params = if (account.region == ApiRegion.CHINESE) {
            mapOf("uid" to account.uid, "region" to account.server)
        } else mapOf("lang" to language)
        return transport.send(
            url, "sign", cookie, account.region, language, "POST",
            params, null, headers
        )
    }

    suspend fun claimDailyRewardItem(
        cookie: String,
        account: ApiAccount,
        challenge: Map<String, String> = emptyMap(),
        reward: Boolean = true
    ): RewardItem? {
        val sign = claimDailyReward(cookie, account, challenge)
        if (sign.optInt("gt_result") == 1 || sign.optBoolean("is_risk")) {
            throw HoYoApiException("本人確認が必要です", 10035)
        }
        if (!reward) return null
        return coroutineScope {
            val status = async { api.call(cookie, account, "get_reward_info") }
            val rewards = async { UpstreamModelParser.rewards(api.call(cookie, account, "get_monthly_rewards")) }
            rewards.await().getOrNull(status.await().optInt("total_sign_day") - 1)
        }
    }

    suspend fun redeemCode(
        cookie: String,
        account: ApiAccount,
        code: String
    ): JSONObject {
        require(code.isNotBlank())
        val origin = when (account.game) {
            ApiGame.GENSHIN -> "https://genshin.hoyoverse.com"
            ApiGame.STARRAIL -> "https://hsr.hoyoverse.com"
            ApiGame.ZZZ -> "https://zenless.hoyoverse.com"
            ApiGame.TOT -> "https://tot.hoyoverse.com"
            ApiGame.HONKAI -> throw UnsupportedOperationException("Code redemption is unsupported for HI3.")
        }
        require(account.region == ApiRegion.OVERSEAS)
        val target = when (account.game) {
            ApiGame.GENSHIN -> "https://public-operation-hk4e.hoyoverse.com/common/apicdkey/api/webExchangeCdkey"
            ApiGame.STARRAIL -> "https://public-operation-hkrpg.hoyoverse.com/common/apicdkey/api/webExchangeCdkeyRisk"
            ApiGame.ZZZ -> "https://public-operation-nap.hoyoverse.com/common/apicdkey/api/webExchangeCdkeyRisk"
            ApiGame.TOT -> "https://public-operation-common.hoyoverse.com/common/apicdkey/api/webExchangeCdkey"
            else -> error("unsupported")
        }
        val gameBiz = when (account.game) {
            ApiGame.GENSHIN -> "hk4e_global"
            ApiGame.STARRAIL -> "hkrpg_global"
            ApiGame.ZZZ -> "nap_global"
            ApiGame.TOT -> "nxx_global"
            else -> error("unsupported")
        }
        val args = mapOf(
            "uid" to account.uid, "region" to account.server, "cdkey" to code,
            "game_biz" to gameBiz, "lang" to language.substringBefore('-')
        )
        val post = account.game in setOf(ApiGame.STARRAIL, ApiGame.ZZZ)
        val json = if (post) JSONObject(args).put("device_uuid", UUID.randomUUID().toString())
            .put("platform", "4").put("t", System.currentTimeMillis() / 1000) else null
        return transport.send(
            target, "", cookie, account.region, language,
            if (post) "POST" else "GET",
            if (post) emptyMap() else args,
            json,
            mapOf("Origin" to origin, "Referer" to origin + "/")
        )
    }

    suspend fun updateSettings(
        cookie: String,
        account: ApiAccount,
        settingId: Int,
        public: Boolean
    ): JSONObject {
        require(settingId in 1..3)
        val gameId = when (account.game) {
            ApiGame.HONKAI -> 1
            ApiGame.GENSHIN -> 2
            ApiGame.STARRAIL -> 6
            ApiGame.ZZZ -> 8
            ApiGame.TOT -> throw UnsupportedOperationException("Unsupported game")
        }
        val target = if (account.region == ApiRegion.CHINESE) {
            "https://api-takumi-record.mihoyo.com/game_record/app/card/wapi"
        } else "https://bbs-api-os.hoyolab.com/game_record/card/wapi"
        return transport.send(
            target, "changeDataSwitch", cookie, account.region, language, "POST",
            emptyMap(),
            JSONObject().put("switch_id", settingId).put("is_public", public).put("game_id", gameId),
            rpcHeaders = true, signed = true
        )
    }

    suspend fun setCalculatorSync(cookie: String, account: ApiAccount, enabled: Boolean): JSONObject {
        require(account.game == ApiGame.GENSHIN)
        val mainland = account.region == ApiRegion.CHINESE
        val prefix = if (mainland) "https://api-takumi.mihoyo.com" else "https://sg-public-api.hoyolab.com"
        val referer = if (mainland) "https://webstatic.mihoyo.com/ys/event/e20200923adopt_calculator/index.html"
            else "https://act.hoyolab.com/"
        return transport.send(prefix, "event/e20200928calculate/v1/avatar/auth", cookie, account.region,
            language, "POST", emptyMap(), JSONObject().put("avatar_auth", if (enabled) 1 else 0).put("lang", language),
            mapOf("Referer" to referer))
    }

    suspend fun setVisibility(cookie: String, account: ApiAccount, public: Boolean): JSONObject =
        updateSettings(cookie, account, 1, public)

    suspend fun setTopGenshinCharacters(
        cookie: String,
        account: ApiAccount,
        characterIds: List<Int>
    ): JSONObject {
        require(account.game == ApiGame.GENSHIN)
        require(characterIds.size in 1..8 && characterIds.distinct().size == characterIds.size)
        val url = if (account.region == ApiRegion.CHINESE) {
            "https://api-takumi-record.mihoyo.com/game_record/app/genshin/api"
        } else "https://sg-public-api.hoyolab.com/event/game_record/genshin/api"
        return transport.send(
            url, "character/top", cookie, account.region, language, "POST", emptyMap(),
            JSONObject().put("avatar_ids", JSONArray(characterIds))
                .put("uid_key", account.uid).put("server_key", account.server),
            rpcHeaders = true, signed = true
        )
    }

    suspend fun finishMimoTask(
        cookie: String, account: ApiAccount, taskId: Long, gameId: Int, versionId: Int
    ): JSONObject = mimo(cookie, account, "finish-task", gameId, versionId,
        mapOf("task_id" to taskId.toString()), true)

    suspend fun claimMimoTaskReward(
        cookie: String, account: ApiAccount, taskId: Long, gameId: Int, versionId: Int
    ): JSONObject = mimo(cookie, account, "receive-point", gameId, versionId,
        mapOf("task_id" to taskId.toString()), gameId == 2)

    suspend fun buyMimoShopItem(
        cookie: String, account: ApiAccount, itemId: Long, gameId: Int, versionId: Int
    ): JSONObject = mimo(cookie, account, "exchange", gameId, versionId,
        mapOf("award_id" to itemId.toString()), true)

    suspend fun drawMimoLottery(
        cookie: String, account: ApiAccount, gameId: Int, versionId: Int
    ): JSONObject = mimo(cookie, account, "lottery", gameId, versionId, emptyMap(), true)

    private suspend fun mimo(
        cookie: String, account: ApiAccount, endpoint: String,
        gameId: Int, versionId: Int, args: Map<String, String>, post: Boolean
    ): JSONObject {
        require(account.region == ApiRegion.OVERSEAS)
        val url = "https://sg-public-api.hoyolab.com/event/e2023mimotravel"
        val path = if (gameId == 2 || account.game == ApiGame.GENSHIN) "qiuqiu/" + endpoint.replace('-', '_') else endpoint
        val data = args + mapOf(
            "game_id" to gameId.toString(), "version_id" to versionId.toString(), "lang" to language
        )
        val body = if (post && endpoint != "receive-point") JSONObject().apply {
            data.forEach { (key, value) -> put(key, value.toLongOrNull() ?: value) }
        } else null
        return transport.send(
            url, path, cookie, account.region, language,
            if (post) "POST" else "GET",
            if (body == null) data else emptyMap(), body
        )
    }

    suspend fun replyToPost(cookie: String, account: ApiAccount, postId: Long, content: String): JSONObject {
        require(content.isNotBlank())
        val escaped = content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val body = JSONObject()
            .put("post_id", postId.toString())
            .put("content", "<p>" + escaped + "</p>")
            .put("image_list", JSONArray())
            .put("reply_bubble_id", "")
            .put("structured_content", JSONArray().put(JSONObject().put("insert", content + "\n")).toString())
        return community(cookie, account, "community/post/wapi/releaseReply", body,
            mapOf("x-rpc-device_id" to UUID.randomUUID().toString()))
    }

    suspend fun deleteReply(cookie: String, account: ApiAccount, replyId: Long, postId: Long): JSONObject =
        community(cookie, account, "community/post/wapi/deleteReply",
            JSONObject().put("reply_id", replyId).put("post_id", postId))

    suspend fun joinTopic(cookie: String, account: ApiAccount, topicId: Long): JSONObject =
        community(cookie, account, "community/topic/wapi/join",
            JSONObject().put("topic_id", topicId).put("is_cancel", false))

    suspend fun leaveTopic(cookie: String, account: ApiAccount, topicId: Long): JSONObject =
        community(cookie, account, "community/topic/wapi/join",
            JSONObject().put("topic_id", topicId).put("is_cancel", true))

    suspend fun accompanyCharacter(
        cookie: String, account: ApiAccount, roleId: Long, topicId: Long
    ): JSONObject {
        require(account.region == ApiRegion.OVERSEAS)
        return transport.send(
            "https://bbs-api-os.hoyolab.com", "community/apihub/api/user/accompany/role",
            withStuid(cookie), account.region, language, "GET",
            mapOf("role_id" to roleId.toString(), "topic_id" to topicId.toString()), null,
            mapOf("Referer" to "https://www.hoyolab.com/"), rpcHeaders = true, signed = true
        )
    }

    suspend fun browseAccompanyPost(
        cookie: String, account: ApiAccount, postId: Long, topicId: Long
    ): JSONObject {
        require(account.region == ApiRegion.OVERSEAS)
        return transport.send(
            "https://bbs-api-os.hoyolab.com", "community/post/api/getPostFull",
            withStuid(cookie), account.region, language, "GET",
            mapOf(
                "post_id" to postId.toString(), "source_topic_id" to topicId.toString(),
                "source_type" to "SourceType_Role"
            ), null, mapOf("Referer" to "https://www.hoyolab.com/"),
            rpcHeaders = true, signed = true
        )
    }

    suspend fun setAccompanyVoiceSetting(
        cookie: String, account: ApiAccount, gameId: Int, roleId: Long,
        voiceLanguage: String, scriptLanguage: String, syncAllRoles: Boolean = false
    ): JSONObject {
        val body = JSONObject().put("game_id", gameId).put("role_id", roleId)
            .put("setting", JSONObject().put("voice_script_setting",
                JSONObject().put("voice_lang", voiceLanguage).put("script_lang", scriptLanguage)
                    .put("setting_unix", System.currentTimeMillis())))
            .put("sync_all_roles", syncAllRoles)
            .put("types", JSONArray().put("SettingTypeVoiceScript"))
        return community(cookie, account, "community/user/api/role_setting/set", body)
    }

    private suspend fun community(
        cookie: String, account: ApiAccount, endpoint: String, body: JSONObject,
        headers: Map<String, String> = emptyMap()
    ): JSONObject {
        val base = if (account.region == ApiRegion.CHINESE) {
            "https://bbs-api.mihoyo.com"
        } else "https://bbs-api-os.hoyolab.com"
        return transport.send(
            base, endpoint, cookie, account.region, language, "POST", emptyMap(), body,
            mapOf("Referer" to if (account.region == ApiRegion.CHINESE) "https://bbs.mihoyo.com/" else "https://www.hoyolab.com/") + headers,
            rpcHeaders = true, signed = true
        )
    }

    private fun gameShortName(game: ApiGame): String = when (game) {
        ApiGame.GENSHIN -> "hk4e"
        ApiGame.STARRAIL -> "hkrpg"
        ApiGame.ZZZ -> "zzz"
        ApiGame.HONKAI -> "bh3"
        ApiGame.TOT -> "nxx"
    }

    private fun rewardUrl(account: ApiAccount): String {
        return if (account.region == ApiRegion.CHINESE) {
            when (account.game) {
                ApiGame.GENSHIN -> "https://api-takumi.mihoyo.com/event/luna/hk4e?act_id=e202311201442471"
                ApiGame.HONKAI -> "https://api-takumi.mihoyo.com/event/luna/bh3?act_id=e202306201626331"
                ApiGame.STARRAIL -> "https://api-takumi.mihoyo.com/event/luna/hkrpg?act_id=e202304121516551"
                ApiGame.ZZZ -> "https://api-takumi.mihoyo.com/event/luna/zzz?act_id=e202406242138391"
                ApiGame.TOT -> "https://api-takumi.mihoyo.com/event/luna/nxx?act_id=e202202251749321"
            }
        } else {
            when (account.game) {
                ApiGame.GENSHIN -> "https://sg-hk4e-api.hoyolab.com/event/sol?act_id=e202102251931481"
                ApiGame.HONKAI -> "https://sg-public-api.hoyolab.com/event/mani?act_id=e202110291205111"
                ApiGame.STARRAIL -> "https://sg-public-api.hoyolab.com/event/luna/hkrpg/os?act_id=e202303301540311"
                ApiGame.ZZZ -> "https://sg-public-api.hoyolab.com/event/luna/zzz/os?act_id=e202406031448091"
                ApiGame.TOT -> "https://sg-public-api.hoyolab.com/event/luna/nxx/os?act_id=e202202281857121"
            }
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
}
