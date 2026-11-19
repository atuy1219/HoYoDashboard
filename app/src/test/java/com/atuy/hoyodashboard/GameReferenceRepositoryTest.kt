package com.atuy.hoyodashboard

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameReferenceRepositoryTest {
    @Test
    fun remoteDescriptionRetainsNestedReferencesAndLineBreaks() {
        val url = checkNotNull(gameReferenceUrl("N11130008"))
        val body = page(url, "万流帰寂", "説明文\\n{LINK#S11142}&lt;color=#FFD780FF&gt;極悪技・閃&lt;/color&gt;{/LINK}")
        val entry = checkNotNull(parseGameReferencePage(url, body))
        assertEquals("万流帰寂", entry.title)
        assertEquals("説明文<br>{LINK#S11142}<color=#FFD780FF>極悪技・閃</color>{/LINK}", entry.description)
        assertNull(parseGameReferencePage(checkNotNull(gameReferenceUrl("S11142")), body))
    }

    @Test
    fun remoteReferencesAreRequestedOnlyWhenNeededAndCachedById() = runBlocking {
        var requests = 0
        val repository = GameReferenceRepository { url ->
            requests++
            page(url, "参照先の名前", "説明文")
        }
        assertNull(repository.get("UNSUPPORTED_1"))
        assertEquals(0, requests)
        assertEquals("参照先の名前", repository.get("N11130008")?.title)
        assertEquals("説明文", repository.get("N11130008")?.description)
        assertEquals(1, requests)
        assertEquals("説明文", repository.get("S11142")?.description)
        assertEquals(2, requests)
    }

    private fun page(url: String, title: String, description: String): String = """
        <html><head><link rel="canonical" href="$url"></head><body>
        <h1>$title</h1>
        <article class="text-field">
          <span class="text-field__role">Description</span>
          <details class="gametext-source"><pre>$description</pre></details>
        </article>
        </body></html>
    """.trimIndent()
}
