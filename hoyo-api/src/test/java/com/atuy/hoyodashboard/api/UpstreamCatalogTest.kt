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
