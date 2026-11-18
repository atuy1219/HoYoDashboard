package com.atuy.hoyodashboard

import com.atuy.hoyodashboard.api.HoYoApiException
import com.atuy.hoyodashboard.api.japaneseApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JapaneseGameTermsTest {
    @Test
    fun knownAndUnrecognizedFieldsUseJapaneseLabels() {
        assertEquals("心象映画", fieldLabel("mindscape"))
        assertEquals("会心ダメージ", fieldLabel("crit_dmg"))
        assertEquals("エージェント・挑戦・スコア", fieldLabel("agentChallengeScore"))
        assertEquals("項目", fieldLabel("undocumented_payload"))
    }

    @Test
    fun statusCodesAndGameTermsUseJapanese() {
        assertEquals("営業中", localizedApiValue("sale_state", "SaleStateDoing", null))
        assertEquals("前期", localizedApiValue("schedule_type", "2", "get_starrail_challenge"))
        assertEquals("キャラクターイベント跳躍", localizedApiValue("gacha_type", "GachaType_AvatarUp", null))
        assertEquals("エーテル", localizedApiValue("element_type", "205", "get_zzz_agents"))
        assertEquals("台湾・香港・マカオ", gameServerName("os_cht"))
        assertEquals("交換コードの有効期限が切れています", japaneseApiError(HoYoApiException("expired", -2001)))
        assertFalse(japaneseApiError(HoYoApiException("English server error", 987)).contains("English"))
    }
}
