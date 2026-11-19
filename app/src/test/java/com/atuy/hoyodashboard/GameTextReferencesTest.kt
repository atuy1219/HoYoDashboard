package com.atuy.hoyodashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameTextReferencesTest {
    @Test
    fun gameMarkupBecomesClickableWithoutShowingIdentifiers() {
        val text = "固有天賦{LINK#N11130008} 万流帰寂{/LINK)の効果。{LINK#S11142}極悪技・閃{/LINK}"
        assertEquals("固有天賦 万流帰寂の効果。極悪技・閃", text.withoutGameReferenceMarkup())
        assertEquals(
            "固有天賦<a href=\"hoyodashboard-reference:N11130008\"> 万流帰寂</a>の効果。<a href=\"hoyodashboard-reference:S11142\">極悪技・閃</a>",
            text.withGameReferenceAnchors()
        )
        assertEquals("N11130008", gameReferenceId("hoyodashboard-reference:N11130008"))
        assertNull(gameReferenceId("hoyodashboard-reference:N11130008/other"))
    }

    @Test
    fun resolvesSkillByIdAndGlossaryByUniqueName() {
        val root = JSONObject("""{
            "skills":[
                {"skill_id":11142,"name":"極悪技・閃","desc":"元素スキルの説明"},
                {"skill_id":11143,"name":"別の天賦","desc":"別の説明"},
                {"name":"万流帰寂","desc":"固有天賦の説明","icon":"https://example.org/icon.png"}
            ],
            "constellations":[{"name":"流断","effect":"{LINK#N11130008}万流帰寂{/LINK}"}]
        }""")
        val index = GameReferenceIndex(root)
        assertEquals("元素スキルの説明", index.find(GameTextReference("S11142", "異なる名前"))?.description)
        assertEquals("固有天賦の説明", index.find(GameTextReference("N11130008", " 万流帰寂 "))?.description)
        assertNull(index.find(GameTextReference("N999999", "不明")))
    }

    @Test
    fun knownReferenceTypesUseVerifiedJapaneseDestinations() {
        assertEquals("https://ghost.mwb.moe/ja/genshin/game_link/N11130008", gameReferenceUrl("N11130008"))
        assertEquals("https://ghost.mwb.moe/ja/genshin/avatar_skill/11142", gameReferenceUrl("S11142"))
        assertNull(gameReferenceUrl("X123"))
        assertNull(gameReferenceUrl("N11130008?input=bad"))
    }
}
