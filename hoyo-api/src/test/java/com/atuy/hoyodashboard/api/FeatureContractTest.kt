package com.atuy.hoyodashboard.api

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            val item = JSONObject().put("id", calls.size.toString()).put("item_name", "エージェント").put("rarity", "S").put("date", date)
            JSONObject().put("gacha_item_list", JSONArray().put(item))
        }
        val records = UpstreamFeatureClient(api, "", zzz).gachaHistory(perBannerLimit = 1)
        assertEquals(6, calls.size)
        assertEquals(true, calls[1].contains("gacha_type=GACHA_TYPE_CHARACTER_UP"))
        assertEquals(6, records.size)
        assertEquals(4, records.first().rarity)
        assertEquals("2026-11-18T19:02", records.first().time)
    }

    @Test
    fun genshinTcgUsesOffsetsAndFiltersWhileKeepingCallerLimitLocal() = runBlocking {
        val queries = mutableListOf<String>()
        val api = fixture { url, _ ->
            val query = url.query
            queries.add(query)
            assertEquals("/event/game_record/genshin/api/gcg/cardList", url.path)
            assertTrue(query.contains("role_id=800000001"))
            assertTrue(query.contains("need_avatar=false"))
            assertTrue(query.contains("need_action=true"))
            assertTrue(query.contains("limit=2"))
            assertFalse(query.contains("page_size="))
            JSONObject().put("card_list", JSONArray().put(JSONObject().put("id", 2 * queries.size - 1))
                .put(JSONObject().put("id", 2 * queries.size)))
        }
        val result = api.call("", genshin, "genshin_tcg", mapOf("page_size" to "2", "limit" to "3", "characters" to "false"))
        assertTrue(queries[0].contains("offset=0"))
        assertTrue(queries[1].contains("offset=2"))
        assertEquals(3, result.getJSONArray("list").length())
    }

    @Test
    fun uidLanguageAndPreviousPeriodAreTranslatedToWireOptions() = runBlocking {
        val api = UpstreamApiClient(transport = transport(request = { request ->
            assertEquals("fr-fr", request.getRequestProperty("x-rpc-language"))
        }) { url, _ ->
            assertEquals("bbs-api-os.hoyolab.com", url.host)
            assertTrue(url.query.contains("server=prod_official_usa"))
            assertTrue(url.query.contains("role_id=600000042"))
            assertTrue(url.query.contains("schedule_type=2"))
            assertFalse(url.query.contains("lang="))
            assertFalse(url.query.contains("previous="))
            JSONObject("""{"challenge_time":{"year":2026,"month":11,"day":18,"hour":10}}""")
        })
        val previous = api.call("", hsr, "get_starrail_challenge",
            mapOf("uid" to "600000042", "lang" to "fr-fr", "previous" to "true"))
        assertEquals("2026-11-18T10:00-05:00", previous.getString("challenge_time"))
        val raw = api.call("", hsr, "get_starrail_challenge",
            mapOf("uid" to "600000042", "lang" to "fr-fr", "previous" to "true", "raw" to "true"))
        assertEquals(2026, raw.getJSONObject("challenge_time").getInt("year"))
    }

    @Test
    fun starRailFiveStarHistoryUsesBadgeWireTypesAndVersionCursor() = runBlocking {
        var pages = 0
        var badgeLogins = 0
        val api = UpstreamApiClient(transport = transport(
            headers = mapOf("Set-Cookie" to listOf("e_hkrpg_token=test-token; Secure", "e_lrsag=test-affinity; Secure"))
        ) { url, body ->
            if (url.path.endsWith("/login/account")) {
                badgeLogins++
                assertEquals(hsr.uid, body.getString("uid"))
                return@transport JSONObject()
            }
            assertTrue(url.query.contains("gacha_type=GachaType_AvatarUp"))
            assertTrue(url.query.contains("uid=${hsr.uid}"))
            assertTrue(url.query.contains("region=${hsr.server}"))
            assertFalse(url.query.contains("role_id="))
            pages++
            if (pages == 1) JSONObject(
                """{"version_id":7,"has_more":true,"next_max_id":"52","list":[{"got_item":false,"gacha_count":13}]}"""
            ) else {
                assertTrue(url.query.contains("max_id=52"))
                assertTrue(url.query.contains("version_id=7"))
                JSONObject("""{"version_id":7,"has_more":false,"list":[{"id":"12","got_item":true,"gacha_count":74,"is_up":true,"item":{"item_id":10001,"name":"限定キャラクター","rarity":5}}]}""")
            }
        })
        val history = UpstreamFeatureClient(api, "", hsr).starRailFiveStarWarps(11)
        assertEquals(1, badgeLogins)
        assertEquals(2, pages)
        assertEquals(13, history.pity)
        assertEquals(7, history.versionId)
        assertEquals("限定キャラクター", history.warps.single().item.name)
        assertEquals(74, history.warps.single().count)
    }

    @Test
    fun recordAutoAuthRequiresAnExplicitOptionAndOnlyAppliesToOwnAccount() = runBlocking {
        var reads = 0
        var writes = 0
        val api = UpstreamApiClient(transport = transport(envelope = { it }) { url, body ->
            if (url.path.endsWith("changeDataSwitch")) {
                writes++
                assertEquals(3, body.getInt("switch_id"))
                assertTrue(body.getBoolean("is_public"))
                return@transport JSONObject().put("retcode", 0).put("data", JSONObject())
            }
            reads++
            if (writes == 0) JSONObject().put("retcode", 10102)
            else JSONObject("""{"retcode":0,"data":{"current_resin":20}}""")
        })
        fun assertNotPublic(error: Throwable?) {
            assertEquals(10102, (error as? HoYoApiException)?.code)
        }
        assertNotPublic(runCatching { api.call("ltuid=1", genshin, "get_genshin_notes") }.exceptionOrNull())
        assertNotPublic(runCatching {
            api.call("ltuid=1", genshin, "get_genshin_notes", mapOf("uid" to "800000002", "autoauth" to "true"))
        }.exceptionOrNull())
        assertEquals(0, writes)
        assertEquals(20, api.call("ltuid=1", genshin, "get_genshin_notes", mapOf("autoauth" to "true")).getInt("current_resin"))
        assertEquals(4, reads)
        assertEquals(1, writes)
    }

    @Test
    fun starRailEquipmentNamesAreEnrichedAndSimpleResponseKeepsOnlyPublicDetails() = runBlocking {
        val raw = JSONObject("""{"avatar_list":[{"id":1002,"name":"丹恒","relics":[{"id":4,"main_property":{"property_type":1,"value":"5%"}}],"skills":[{"name":"技"}]}],"property_info":{"1":{"name":"会心率"}},"recommend_property":{"1002":{"recommend_relic_properties":[1]}}}""")
        val api = fixture { _, _ -> JSONObject(raw.toString()) }
        val builds = UpstreamFeatureClient(api, "", hsr).characterBuilds()
        val stat = builds.single().equipment.single().stats.single()
        assertEquals("会心率", stat.name)
        assertEquals("5%", stat.value)
        assertTrue(stat.recommended)
        val simple = api.call("", hsr, "get_starrail_characters", mapOf("simple" to "true"))
            .getJSONArray("avatar_list").getJSONObject(0)
        assertFalse(simple.has("skills"))
        assertFalse(simple.getJSONArray("relics").getJSONObject(0).has("main_property"))
    }

    @Test
    fun tcgDeckAndLineupFiltersUseCorrectQueryAndJsonBody() = runBlocking {
        val api = fixture { url, body ->
            if (url.path.endsWith("lineup/index")) {
                assertTrue(url.query.contains("roles=1&roles=2"))
                assertTrue(url.query.contains("order=Match"))
                assertTrue(url.query.contains("uid=800000001"))
                assertTrue(url.query.contains("limit=2"))
            } else {
                assertEquals("lang=fr-fr", url.query)
                assertEquals("O_LATEST", body.getString("order"))
                assertEquals(4, body.getInt("label_id"))
                assertEquals(listOf(1, 2), (0..1).map(body.getJSONArray("role_card_id")::getInt))
                assertFalse(body.has("lang"))
            }
            JSONObject("""{"list":[],"next_page_token":""}""")
        }
        api.call("", genshin, "get_lineups", mapOf("characters" to "1,2", "match_characters" to "true", "page_size" to "2"))
        val decks = api.call("", genshin, "get_tcg_decks",
            mapOf("label" to "4", "characters" to "[1,2]", "newest" to "true", "lang" to "fr-fr"))
        assertEquals(0, decks.getJSONArray("list").length())
    }

    @Test
    fun appLoginCookieRefreshUsesExistingStokenAndMintsV2Tokens() = runBlocking {
        val transport = transport(request = { connection ->
            val cookie = connection.getRequestProperty("Cookie")
            assertTrue(cookie.contains("stoken=v2-test"))
            assertTrue(cookie.contains("mid=test-mid"))
            assertEquals("c9oqaq3s3gu8", connection.getRequestProperty("x-rpc-app_id"))
            assertEquals(3, connection.getRequestProperty("DS").split(',').size)
        }) { url, body ->
            assertTrue(url.path.endsWith("/token/getBySToken"))
            assertEquals(listOf(2, 4), (0..1).map(body.getJSONArray("dst_token_types")::getInt))
            JSONObject("""{"tokens":[{"token_type":2,"token":"new-ltoken"},{"token_type":4,"token":"new-cookie-token"}]}""")
        }
        val session = CookieSessionManager(onCookieUpdate = null, transport = transport)
        val updated = session.refresh("stoken=v2-test; ltmid_v2=test-mid; ltuid_v2=42", ApiRegion.OVERSEAS)
        assertTrue(updated.orEmpty().contains("ltoken_v2=new-ltoken"))
        assertTrue(updated.orEmpty().contains("cookie_token_v2=new-cookie-token"))
    }

    @Test
    fun fullGachaLinkIsDecodedWithoutOpeningItsUrl() = runBlocking {
        val api = fixture { url, _ ->
            val token = url.query.split('&').first { it.startsWith("authkey=") }
            assertEquals("authkey=abc%2Bdef%3D", token)
            JSONObject("""{"list":[]}""")
        }
        val result = api.call("", genshin, "wish_history", mapOf(
            "banner_type" to "301", "limit" to "1",
            "authkey" to "https://example.invalid/history?other=1&authkey=abc%2Bdef%3D"
        ))
        assertEquals(0, result.getJSONArray("list").length())
    }

    private fun fixture(response: (URL, JSONObject) -> JSONObject) = UpstreamApiClient(transport = transport(response = response))

    private fun transport(
        headers: Map<String?, List<String>> = emptyMap(),
        envelope: (JSONObject) -> JSONObject = { JSONObject().put("retcode", 0).put("data", it) },
        request: (HttpURLConnection) -> Unit = {},
        response: (URL, JSONObject) -> JSONObject
    ) = ApiTransport(null) { url ->
            object : HttpURLConnection(url) {
                private val body = ByteArrayOutputStream()
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode(): Int {
                    request(this)
                    return 200
                }
                override fun getOutputStream() = body
                override fun getHeaderFields() = headers
                override fun getInputStream() = envelope(
                    response(url, if (body.size() == 0) JSONObject() else JSONObject(body.toString("UTF-8")))
                ).toString().byteInputStream()
            }
        }
}
