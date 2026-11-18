package com.atuy.hoyodashboard.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamCatalogTest {
    @Test
    fun allMethodsHaveUniqueNames() {
        val names = UpstreamCatalog.methods.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun catalogCoversAllClientComponents() {
        assertTrue(UpstreamCatalog.methods.size >= 117)
        assertTrue(UpstreamCatalog.find("get_genshin_spiral_abyss") != null)
        assertTrue(UpstreamCatalog.find("get_starrail_pure_fiction") != null)
        assertTrue(UpstreamCatalog.find("get_deadly_assault") != null)
        assertTrue(UpstreamCatalog.find("get_honkai_user") != null)
        assertTrue(UpstreamCatalog.find("get_monthly_rewards") != null)
        assertTrue(UpstreamCatalog.find("get_tcg_decks") != null)
        assertTrue(UpstreamCatalog.find("get_starrail_warp_brief") != null)
    }

    @Test
    fun regionAvailabilityHidesUnsupportedMethods() {
        val client = UpstreamApiClient()
        val overseas = ApiAccount(ApiGame.GENSHIN, "800000001", "os_asia", ApiRegion.OVERSEAS)
        val china = ApiAccount(ApiGame.GENSHIN, "100000001", "cn_gf01", ApiRegion.CHINESE)
        val overseasNames = client.available(overseas).map { it.name }.toSet()
        val chinaNames = client.available(china).map { it.name }.toSet()

        assertTrue("get_mimo_games" in overseasNames)
        assertTrue("get_web_events" in overseasNames)
        assertTrue("transaction_log" in overseasNames)
        assertFalse("get_mimo_games" in chinaNames)
        assertFalse("get_web_events" in chinaNames)
        assertFalse("get_accompany_character_details" in chinaNames)
        assertFalse("transaction_log" in chinaNames)
        assertTrue("get_genshin_diary" in chinaNames)
    }

    @Test
    fun uiEntriesRespectGameScope() {
        val genshin = UpstreamCatalog.forGame(Game.GENSHIN).map { it.name }
        assertTrue("get_genshin_notes" in genshin)
        assertFalse("get_starrail_notes" in genshin)
        assertFalse("get_zzz_notes" in genshin)
    }

    @Test
    fun gachaNeedsIndependentAuthKey() {
        val method = UpstreamCatalog.find("wish_history")
        assertTrue(method?.authKeyRequired == true)
        assertTrue(method?.readOnly == true)
    }

    @Test
    fun allCatalogEntriesAreReadOnly() {
        assertTrue(UpstreamCatalog.methods.all { it.readOnly })
    }
}
