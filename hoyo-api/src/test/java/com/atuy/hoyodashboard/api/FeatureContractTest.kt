package com.atuy.hoyodashboard.api

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class FeatureContractTest {
    private val genshin = ApiAccount(ApiGame.GENSHIN, "800000001", "os_asia")
    private val hsr = ApiAccount(ApiGame.STARRAIL, "800000001", "prod_official_asia")
    private val zzz = ApiAccount(ApiGame.ZZZ, "1300000010", "prod_gf_jp")

    @Test
    fun calculatorListsFollowPagesWithNumericPayloadAndLanguage() = runBlocking {
        val calls = mutableListOf<Int>()
        val api = fixture { _, body ->
            val page = body.getInt("page")
            assertEquals(200, body.getInt("size"))
            assertEquals("ja-jp", body.getString("lang"))
            assertEquals(0, body.getJSONArray("element_attr_ids").length())
            calls.add(page)
            JSONObject().put("total", 201).put("list", JSONArray().put(JSONObject().put("id", page)))
        }
        val list = api.call("", genshin, "get_calculator_characters").getJSONArray("list")
        assertEquals(listOf(1, 2), calls)
        assertEquals(2, list.length())
    }

    @Test
    fun calculatorSingleAndBatchUseUpstreamEndpointsAndBodies() = runBlocking {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        val api = fixture { url, body -> JSONObject().also { requests.add(url.path to body) } }
        val calculator = EnhancementCalculator(UpstreamFeatureClient(api, "", genshin))
        val plan = EnhancementPlan(
            character = LevelUpgrade(10000003, 20, 80), weapon = LevelUpgrade(11501, 1, 90),
            artifacts = listOf(LevelUpgrade(75543, 0, 20)), talents = listOf(LevelUpgrade(10331, 1, 8))
        )
        calculator.calculate(plan)
        calculator.calculateBatch(listOf(plan, plan))
        calculator.calculateFurnishings(listOf(FurnishingQuantity(100, 2), FurnishingQuantity(100, 3)))
        assertEquals(listOf("v2/compute", "v3/batch_compute", "v1/furniture/compute"),
            requests.map { it.first.substringAfter("/event/e20200928calculate/") })
        assertEquals(80, requests[0].second.getInt("avatar_level_target"))
        assertEquals(11501, requests[0].second.getJSONObject("weapon").getInt("id"))
        assertEquals(75543, requests[0].second.getJSONArray("reliquary_list").getJSONObject(0).getInt("id"))
        assertEquals(2, requests[1].second.getJSONArray("items").length())
        assertEquals(5, requests[2].second.getJSONArray("list").getJSONObject(0).getInt("cnt"))
    }

    @Test
    fun tcgCardsUseRolesAndActionsAndLookupTheCorrectEffectRoute() = runBlocking {
        val paths = mutableListOf<String>()
        val api = fixture { url, body ->
            paths.add(url.path)
            when (url.path.substringAfterLast('/')) {
                "roles" -> JSONObject().put("roles", JSONArray().put(JSONObject().put("id", 1001)))
                "actions" -> {
                    assertEquals(1001, body.getJSONArray("role_ids").getInt(0))
                    JSONObject().put("actions", JSONArray().put(JSONObject().put("id", 20001)))
                }
                else -> JSONObject()
            }
        }
        assertEquals(2, api.call("", genshin, "get_tcg_cards").getJSONArray("list").length())
        api.call("", genshin, "get_tcg_card_effect", mapOf("id" to "20001"))
        assertEquals("/event/cardsquare/action/skill", paths.last())
    }

    @Test
    fun zzzDiaryAndStarRailScheduleUseEndpointSpecificParameters() = runBlocking {
        val requests = mutableListOf<URL>()
        val api = fixture { url, _ -> JSONObject().also { requests.add(url) } }
        UpstreamFeatureClient(api, "", zzz).monthlyDiaryEntries("2026-11", 2)
        api.call("", hsr, "get_starrail_lineup_schedules", mapOf("type" to "Story"))
        assertEquals("/event/nap_ledger/month_detail", requests[0].path)
        assertEquals(true, requests[0].query.contains("type=MatserTapeData"))
        assertEquals("/event/rpgsimulator/story_schedule/list", requests[1].path)
        assertEquals(null, requests[1].query)
    }

    @Test
    fun genshinBuildUsesNamesFromPropertyMap() {
        val row = JSONObject("""{"base":{"id":10000003,"name":"ジン"},"relics":[{"main_property":{"property_type":1,"value":"4.7%"}}]}""")
        val propertyMap = JSONObject("""{"1":{"name":"会心率"}}""")
        val output = decorateGenshinCharacter(row, propertyMap)
        assertEquals("ジン", output.getString("name"))
        val property = output.getJSONArray("relics").getJSONObject(0).getJSONObject("main_property")
        assertEquals("会心率", property.getString("name"))
        assertEquals("4.7%", property.getString("value"))
        assertFalse(property.has("property_type"))
    }

    @Test
    fun passwordLoginEncryptsCredentialsAndDelegatesChallenge() = runBlocking {
        val auth = NativeAccountAuth(ApiRegion.OVERSEAS, "test-device", transport(
            headers = mapOf("x-rpc-aigis" to listOf("""{"session_id":"test-session","data":{}}""")),
            envelope = { it }
        ) { url, body ->
            assertEquals("/account/ma-passport/api/webLoginByPassword", url.path)
            assertFalse(body.getString("account").contains("student@example.com"))
            assertFalse(body.getString("password").contains("test-password"))
            assertEquals(6, body.getInt("token_type"))
            JSONObject().put("retcode", -3101)
        })
        val step = auth.loginWithPassword("student@example.com", "test-password") as AccountLoginStep.VerifyChallenge
        assertEquals("test-session", step.challenge.getString("session_id"))
    }

    @Test
    fun qrLoginReturnsCookiesOnlyAfterConfirmation() = runBlocking {
        var polls = 0
        val auth = NativeAccountAuth(ApiRegion.CHINESE, "test-device", transport(
            headers = mapOf("Set-Cookie" to listOf("ltoken_v2=test-token; Secure", "ltuid_v2=42; Secure", "tracking=ignore")),
        ) { url, body ->
            if (url.path.endsWith("createQRLogin")) {
                return@transport JSONObject().put("ticket", "test-ticket").put("url", "https://www.miyoushe.com/qr")
            }
            assertEquals("test-ticket", body.getString("ticket"))
            polls++
            JSONObject().put("status", if (polls == 1) "Scanned" else "Confirmed")
        })
        val qr = auth.createQrLogin()
        val scanned = auth.checkQrLogin(qr)
        assertEquals(QrLoginStatus.SCANNED, scanned.status)
        assertEquals(null, scanned.login)
        val confirmed = auth.checkQrLogin(qr)
        assertEquals(QrLoginStatus.CONFIRMED, confirmed.status)
        assertEquals("ltoken_v2=test-token; ltuid_v2=42", confirmed.login?.cookieHeader())
    }

    @Test
    fun zzzHistoryUsesChronicleTypesAndFormatsRankAndDate() = runBlocking {
        val calls = mutableListOf<String>()
        val api = fixture { url, _ ->
            calls.add(url.query)
            val date = JSONObject("""{"year":2026,"month":11,"day":18,"hour":19,"minute":2,"second":0}""")
            val item = JSONObject().put("id", "42").put("item_name", "エージェント").put("rarity", "S").put("date", date)
            JSONObject().put("gacha_item_list", JSONArray().put(item))
        }
        val records = UpstreamFeatureClient(api, "", zzz).gachaHistory(perBannerLimit = 1)
        assertEquals(6, calls.size)
        assertEquals(true, calls[1].contains("gacha_type=GACHA_TYPE_CHARACTER_UP"))
        assertEquals(5, records.single().rarity)
        assertEquals("2026-11-18T19:02", records.single().time)
    }

    private fun fixture(response: (URL, JSONObject) -> JSONObject) = UpstreamApiClient(transport = transport(response = response))

    private fun transport(
        headers: Map<String?, List<String>> = emptyMap(),
        envelope: (JSONObject) -> JSONObject = { JSONObject().put("retcode", 0).put("data", it) },
        response: (URL, JSONObject) -> JSONObject
    ) = ApiTransport(null) { url ->
            object : HttpURLConnection(url) {
                private val body = ByteArrayOutputStream()
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getOutputStream() = body
                override fun getHeaderFields() = headers
                override fun getInputStream() = envelope(
                    response(url, if (body.size() == 0) JSONObject() else JSONObject(body.toString("UTF-8")))
                ).toString().byteInputStream()
            }
        }
}
