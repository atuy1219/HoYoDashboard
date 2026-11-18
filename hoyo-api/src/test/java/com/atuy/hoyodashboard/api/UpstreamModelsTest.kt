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
                .put("item_id", "1001")
                .put("is_up", 1),
            0
        )
        assertEquals(5, wish.rarity)
        assertEquals(301, wish.bannerType)
        assertEquals(-13, wish.timezoneOffset)
        assertTrue(wish.isUp == true)
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
    fun mergesAllBannerPoolsBeforeApplyingHistoryLimit() {
        val merged = mergeHistoryByTime(
            listOf(
                listOf(
                    JSONObject().put("id", "old").put("time", "2026-11-01 10:00:00"),
                    JSONObject().put("id", "newest").put("time", "2026-11-18 10:00:00")
                ),
                listOf(
                    JSONObject().put("id", "middle").put("time", "2026-11-12 10:00:00"),
                    JSONObject().put("id", "older").put("time", "2026-10-01 10:00:00")
                )
            ),
            2
        )
        assertEquals(listOf("newest", "middle"), merged.map { it.optString("id") })
    }

    @Test
    fun parsesNestedDiaryPayloadsForAllSupportedGames() {
        val genshin = UpstreamModelParser.diary(
            JSONObject().put("data_month", 11).put(
                "month_data",
                JSONObject().put("current_primogems", 1200).put("current_mora", 34000)
            )
        )
        assertEquals(1200L, genshin.currentPrimogems)
        assertEquals(34000L, genshin.currentMora)

        val starRail = UpstreamModelParser.diary(
            JSONObject().put("data_month", "202611").put(
                "month_data",
                JSONObject().put("current_hcoin", 800).put("current_rails_pass", 12)
            )
        )
        assertEquals(800L, starRail.currentHcoin)
        assertEquals(12L, starRail.currentRailsPass)

        val zzz = UpstreamModelParser.diary(
            JSONObject().put("data_month", "202611").put(
                "month_data",
                JSONObject().put(
                    "income",
                    JSONObject().put(
                        "list",
                        org.json.JSONArray().put(
                            JSONObject().put("data_type", "PolychromesData").put("count", 450)
                        )
                    )
                )
            )
        )
        assertEquals(450L, zzz.currentPolychromes)
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
