package com.atuy.hoyodashboard

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameTextReferencesTest {
    @Test
    fun gameMarkupBecomesClickableWithoutShowingIdentifiers() {
        val text = "固有天賦{LINK#N11130008} 万流帰寂{/LINK)の効果。{LINK#S11142}極悪技・閃{/LINK}"
        assertEquals("固有天賦 万流帰寂の効果。極悪技・閃", text.withoutGameReferenceMarkup())
        val result = parseGameReferences(text)
        assertEquals(text.withoutGameReferenceMarkup(), result.text)
        assertEquals(listOf("N11130008", "S11142"), result.links.map { gameReferenceId(it.url) })
        assertEquals(listOf(" 万流帰寂", "極悪技・閃"), result.links.map { result.text.substring(it.start, it.end) })
        assertEquals("N11130008", gameReferenceId("hoyodashboard-reference:N11130008"))
        assertNull(gameReferenceId("hoyodashboard-reference:N11130008/other"))
    }

    @Test
    fun arbitraryReferencesKeepClickableRangesAlongsideWebLinks() {
        val text = "{LINK#N11130008} テキスト{/LINK)・{LINK#CUSTOM_42}別の用語{/LINK}・詳細"
        val web = TextLink(text.lastIndexOf("詳細"), text.length, "https://example.org", "詳細")
        val parsed = parseGameReferences(text, listOf(web))
        assertEquals(" テキスト・別の用語・詳細", parsed.text)
        assertEquals(listOf("N11130008", "CUSTOM_42", null), parsed.links.map { gameReferenceId(it.url) })
        assertEquals(listOf(" テキスト", "別の用語", "詳細"), parsed.links.map { parsed.text.substring(it.start, it.end) })
    }

    @Test
    fun translatedLinkLabelsRemainClickableAndRetainOriginalReference() = runBlocking {
        val parsed = parseGameReferences("Talent {LINK#N11130008}Return to Oblivion{/LINK} applies.")
        val translated = parsed.mapText { fragment ->
            when (fragment) {
                "Talent " -> "固有天賦 "
                "Return to Oblivion" -> "万流帰寂"
                " applies." -> " が発動する。"
                else -> fragment
            }
        }
        assertEquals("固有天賦 万流帰寂 が発動する。", translated.text)
        val link = translated.links.single()
        assertEquals("万流帰寂", translated.text.substring(link.start, link.end))
        assertEquals("Return to Oblivion", link.label)
        assertEquals("N11130008", gameReferenceId(link.url))
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
