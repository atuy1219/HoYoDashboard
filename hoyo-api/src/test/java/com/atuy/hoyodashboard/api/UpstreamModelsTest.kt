package com.atuy.hoyodashboard.api

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamModelsTest {
    @Test
    fun mapsEveryGameToItsApiFamily() {
        assertEquals(ApiGame.GENSHIN, ApiGame.from(Game.GENSHIN))
        assertEquals(ApiGame.STARRAIL, ApiGame.from(Game.STAR_RAIL))
        assertEquals(ApiGame.ZZZ, ApiGame.from(Game.ZZZ))
        assertEquals(ApiGame.HONKAI, ApiGame.from(Game.HONKAI))
        assertEquals(ApiGame.TOT, ApiGame.from(Game.TOT))
    }

    @Test
    fun keepsRegionOnTheNativeAccount() {
        val role = Role(
            game = Game.GENSHIN,
            uid = "100000001",
            nickname = "Traveler",
            server = "cn_gf01",
            region = ApiRegion.CHINESE
        )
        val account = ApiAccount.from(role)
        assertEquals(ApiRegion.CHINESE, account.region)
        assertEquals("cn_gf01", account.server)
    }

    @Test
    fun parsesGenshinProfileWithoutDroppingRawData() {
        val json = JSONObject()
            .put("role", JSONObject().put("nickname", "A").put("level", 60))
            .put("stats", JSONObject().put("active_days", 500).put("achievements", 900))
            .put("avatars", org.json.JSONArray().put(JSONObject().put("id", 1)))
        val profile = UpstreamModelParser.profile(ApiGame.GENSHIN, json) as GenshinProfile
        assertEquals("A", profile.nickname)
        assertEquals(60, profile.level)
        assertEquals(500, profile.activeDays)
        assertEquals(1, profile.avatarCount)
        assertTrue(profile.raw.has("stats"))
    }

    @Test
    fun parsesWishTimezoneAndBannerMetadata() {
        val wish = GachaRecord.from(
            JSONObject()
                .put("id", "123")
                .put("name", "Test")
                .put("rank_type", "5")
                .put("time", "2026-11-18 12:00:00")
                .put("tz_offset", -13)
                .put("gacha_type", 301)
                .put("item_id", "1001"),
            0
        )
        assertEquals(5, wish.rarity)
        assertEquals(301, wish.bannerType)
        assertEquals(-13, wish.timezoneOffset)
        assertTrue(wish.timestamp > 0)
    }

    @Test
    fun gachaCookieCanUseWebviewAccountAliases() = runBlocking {
        var saved: String? = null
        val sessions = CookieSessionManager { saved = it }
        val cookie = "ltuid_v2=123; ltmid_v2=mid-1; cookie_token_v2=token-1"
        val result = sessions.ensureGachaCookie(cookie, ApiRegion.OVERSEAS)
        assertTrue(result.contains("account_id_v2=123"))
        assertTrue(result.contains("account_mid_v2=mid-1"))
        assertEquals(result, saved)
    }

    @Test
    fun catalogExposesHonkaiAndTearsOfThemis() {
        val honkai = UpstreamCatalog.forGame(Game.HONKAI).map { it.name }
        val tot = UpstreamCatalog.forGame(Game.TOT).map { it.name }
        assertTrue("get_honkai_user" in honkai)
        assertTrue("get_honkai_elysian_realm" in honkai)
        assertTrue("get_monthly_rewards" in tot)
    }
}
