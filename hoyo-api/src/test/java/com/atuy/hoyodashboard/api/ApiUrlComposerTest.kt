package com.atuy.hoyodashboard.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiUrlComposerTest {
    @Test
    fun preservesDailyRewardActivityId() {
        val url = ApiUrlComposer.compose(
            "https://sg-hk4e-api.hoyolab.com/event/sol?act_id=abc123",
            "info",
            mapOf("lang" to "ja-jp")
        )
        assertEquals(
            "https://sg-hk4e-api.hoyolab.com/event/sol/info?act_id=abc123&lang=ja-jp",
            url.toString()
        )
    }

    @Test
    fun preservesAbsolutePathWhenEndpointIsEmpty() {
        val url = ApiUrlComposer.compose(
            "https://public-operation-hk4e.hoyoverse.com/common/apicdkey/api/webExchangeCdkey",
            "",
            mapOf("cdkey" to "A+B C")
        )
        assertEquals(
            "https://public-operation-hk4e.hoyoverse.com/common/apicdkey/api/webExchangeCdkey?cdkey=A%2BB+C",
            url.toString()
        )
    }

    @Test
    fun rejectsExternalHosts() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiUrlComposer.compose("https://example.com", "note", emptyMap())
        }
    }

    @Test
    fun rejectsPathTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiUrlComposer.compose("https://bbs-api-os.hoyolab.com", "../private", emptyMap())
        }
    }

    @Test
    fun acceptsOfficialSubdomainsOnly() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiUrlComposer.compose("https://hoyolab.com.example.org", "note", emptyMap())
        }
    }
}
