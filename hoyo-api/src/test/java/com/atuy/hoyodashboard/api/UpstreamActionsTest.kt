package com.atuy.hoyodashboard.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamActionsTest {
    private val actions = UpstreamActions()

    @Test
    fun exposesGameSpecificAndGlobalActionsForOverseasGenshin() {
        val account = ApiAccount(ApiGame.GENSHIN, "800000001", "os_asia")
        val names = actions.availableActions(account).map { it.name }.toSet()

        assertTrue("claim_daily_reward" in names)
        assertTrue("redeem_code" in names)
        assertTrue("set_top_genshin_characters" in names)
        assertTrue("finish_mimo_task" in names)
        assertTrue("reply_to_post" in names)
        assertTrue("delete_reply" in names)
        assertTrue(names.size == 15)
        val definitions = actions.availableActions(account).associateBy { it.name }
        assertTrue(definitions.getValue("delete_reply").confirmationRequired)
        assertTrue(definitions.getValue("buy_mimo_shop_item").confirmationRequired)
        assertTrue(definitions.getValue("set_top_genshin_characters").required == listOf("character_ids"))
    }

    @Test
    fun hidesRegionAndGameRestrictedActions() {
        val china = ApiAccount(ApiGame.GENSHIN, "100000001", "cn_gf01", ApiRegion.CHINESE)
        val honkai = ApiAccount(ApiGame.HONKAI, "100000001", "bh3_os_usa")
        val tears = ApiAccount(ApiGame.TOT, "100000001", "nxx_os_usa")

        val chinaActions = actions.availableActions(china).map { it.name }
        val honkaiActions = actions.availableActions(honkai).map { it.name }
        val tearsActions = actions.availableActions(tears).map { it.name }

        assertFalse("redeem_code" in chinaActions)
        assertFalse("finish_mimo_task" in chinaActions)
        assertFalse("redeem_code" in honkaiActions)
        assertFalse("set_top_genshin_characters" in honkaiActions)
        assertFalse("update_settings" in tearsActions)
    }

    @Test
    fun rejectsMalformedArgumentsBeforeMakingRequest() = runBlocking {
        val account = ApiAccount(ApiGame.GENSHIN, "800000001", "os_asia")
        try {
            actions.execute(
                "",
                account,
                "set_top_genshin_characters",
                mapOf("character_ids" to "1001,not-an-id")
            )
            throw AssertionError("Expected malformed IDs to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("integers"))
        }
    }

    @Test
    fun hidesGlobalOnlyVoiceActionForChinaAccounts() {
        val account = ApiAccount(ApiGame.GENSHIN, "100000001", "cn_gf01", ApiRegion.CHINESE)
        val names = actions.availableActions(account).map { it.name }
        assertFalse("set_accompany_voice_setting" in names)
    }

    @Test
    fun rejectsUnsupportedActionBeforeMakingRequest() = runBlocking {
        val account = ApiAccount(ApiGame.TOT, "100000001", "nxx_os_usa")
        try {
            actions.execute(
                "",
                account,
                "update_settings",
                mapOf("setting_id" to "1", "public" to "false")
            )
            throw AssertionError("Expected unsupported action to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("not available"))
        }
    }
}
