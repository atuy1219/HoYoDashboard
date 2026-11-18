package com.atuy.hoyodashboard.api

enum class ApiGame {
    GENSHIN, STARRAIL, ZZZ, HONKAI, TOT;

    companion object {
        fun from(game: Game): ApiGame = when (game) {
            Game.GENSHIN -> GENSHIN
            Game.STAR_RAIL -> STARRAIL
            Game.ZZZ -> ZZZ
            Game.HONKAI -> HONKAI
            Game.TOT -> TOT
        }
    }
}

enum class ApiScope {
    RECORD, CARD, LEDGER, ZZZ_LEDGER, CULTIVATE, REWARD, CALCULATOR,
    LINEUP, HSR_LINEUP, HSR_WARP, CARD_PLAZA, BOULEUTERION, TEAPOT, WIKI,
    GACHA, TRANSACTION, BBS, MIMO, WEBSTATIC, ANNOUNCEMENT, ACCOUNTS
}

data class ApiMethod(
    val name: String,
    val title: String,
    val category: String,
    val game: ApiGame?,
    val scope: ApiScope,
    val endpoint: String,
    val verb: String = "GET",
    val defaults: Map<String, String> = emptyMap(),
    val required: Set<String> = emptySet(),
    val authKeyRequired: Boolean = false,
    val useUidParam: Boolean = false,
    val requiresBadge: Boolean = false
) {
    val readOnly: Boolean get() = verb == "GET" || verb == "READ_POST"
}

object UpstreamCatalog {
    private fun r(
        name: String,
        title: String,
        category: String,
        game: ApiGame?,
        scope: ApiScope,
        endpoint: String,
        verb: String = "GET",
        defaults: Map<String, String> = emptyMap(),
        required: Set<String> = emptySet(),
        authKeyRequired: Boolean = false,
        useUidParam: Boolean = false,
        requiresBadge: Boolean = false
    ) = ApiMethod(name, title, category, game, scope, endpoint, verb, defaults, required, authKeyRequired, useUidParam, requiresBadge)

    val methods: List<ApiMethod> = listOf(
        r("get_partial_genshin_user", "プレイヤー概要", "プロフィール", ApiGame.GENSHIN, ApiScope.RECORD, "index", defaults = mapOf("avatar_list_type" to "0")),
        r("get_genshin_user", "プレイヤー戦績", "プロフィール", ApiGame.GENSHIN, ApiScope.RECORD, "index"),
        r("get_full_genshin_user", "原神の全戦績", "プロフィール", ApiGame.GENSHIN, ApiScope.RECORD, "index"),

        r("get_genshin_characters", "キャラクター一覧", "キャラクター", ApiGame.GENSHIN, ApiScope.RECORD, "character/list", "READ_POST"),
        r("get_genshin_detailed_characters", "装備・聖遺物・天賦", "キャラクター", ApiGame.GENSHIN, ApiScope.RECORD, "character/detail", "READ_POST"),
        r("get_genshin_spiral_abyss", "深境螺旋", "戦闘記録", ApiGame.GENSHIN, ApiScope.RECORD, "spiralAbyss", defaults = mapOf("schedule_type" to "1")),
        r("get_genshin_spiral_abyss_previous", "深境螺旋（前期）", "戦闘記録", ApiGame.GENSHIN, ApiScope.RECORD, "spiralAbyss", defaults = mapOf("schedule_type" to "2")),
        r("get_imaginarium_theater", "幻想シアター", "戦闘記録", ApiGame.GENSHIN, ApiScope.RECORD, "role_combat", defaults = mapOf("need_detail" to "true")),
        r("get_genshin_notes", "リアルタイムノート", "デイリー", ApiGame.GENSHIN, ApiScope.RECORD, "dailyNote"),
        r("get_genshin_activities", "活動履歴", "戦闘記録", ApiGame.GENSHIN, ApiScope.RECORD, "activities"),
        r("get_genshin_tcg_preview", "七聖召喚戦績", "七聖召喚", ApiGame.GENSHIN, ApiScope.RECORD, "gcg/basicInfo"),
        r("get_genshin_tcg_matches", "七聖召喚対戦履歴", "七聖召喚", ApiGame.GENSHIN, ApiScope.RECORD, "gcg/matchList"),
        r("genshin_tcg", "七聖召喚カード一覧", "七聖召喚", ApiGame.GENSHIN, ApiScope.RECORD, "gcg/cardList", defaults = mapOf("limit" to "30", "need_stats" to "false")),
        r("get_genshin_event_calendar", "イベントカレンダー", "イベント", ApiGame.GENSHIN, ApiScope.RECORD, "act_calendar", "READ_POST"),
        r("get_envisaged_echoes", "追憶のエコー", "戦闘記録", ApiGame.GENSHIN, ApiScope.RECORD, "char_master"),
        r("get_stygian_onslaught", "幽境の激戦", "戦闘記録", ApiGame.GENSHIN, ApiScope.RECORD, "hard_challenge", defaults = mapOf("need_detail" to "true")),

        r("get_starrail_notes", "リアルタイムノート", "デイリー", ApiGame.STARRAIL, ApiScope.RECORD, "note"),
        r("get_starrail_user", "プレイヤー戦績", "プロフィール", ApiGame.STARRAIL, ApiScope.RECORD, "index"),
        r("get_starrail_basic_info", "プレイヤー基本情報", "プロフィール", ApiGame.STARRAIL, ApiScope.RECORD, "role/basicInfo"),
        r("get_starrail_characters", "キャラクター・遺物・光円錐", "キャラクター", ApiGame.STARRAIL, ApiScope.RECORD, "avatar/info", defaults = mapOf("need_wiki" to "true")),
        r("get_starrail_challenge", "忘却の庭", "戦闘記録", ApiGame.STARRAIL, ApiScope.RECORD, "challenge", defaults = mapOf("schedule_type" to "1", "need_all" to "true")),
        r("get_starrail_rogue", "模擬宇宙", "戦闘記録", ApiGame.STARRAIL, ApiScope.RECORD, "rogue", defaults = mapOf("schedule_type" to "1", "need_detail" to "true")),
        r("get_starrail_pure_fiction", "虚構叙事", "戦闘記録", ApiGame.STARRAIL, ApiScope.RECORD, "challenge_story", defaults = mapOf("schedule_type" to "1", "need_all" to "true")),
        r("get_starrail_apc_shadow", "末日の幻影", "戦闘記録", ApiGame.STARRAIL, ApiScope.RECORD, "challenge_boss", defaults = mapOf("schedule_type" to "1", "need_all" to "true")),
        r("get_anomaly_arbitration", "異相演算", "戦闘記録", ApiGame.STARRAIL, ApiScope.RECORD, "challenge_peak", defaults = mapOf("schedule_type" to "3")),
        r("get_starrail_event_calendar", "イベントカレンダー", "イベント", ApiGame.STARRAIL, ApiScope.RECORD, "get_act_calender"),

        r("get_zzz_notes", "リアルタイムノート", "デイリー", ApiGame.ZZZ, ApiScope.RECORD, "note"),
        r("get_zzz_diary", "月次支出・収入", "月次記録", ApiGame.ZZZ, ApiScope.ZZZ_LEDGER, "month_info", defaults = mapOf("month" to "")),
        r("get_zzz_diary_detail", "月次明細", "月次記録", ApiGame.ZZZ, ApiScope.ZZZ_LEDGER, "month_detail", defaults = mapOf("current_page" to "1", "page_size" to "20"), required = setOf("month", "type")),
        r("get_zzz_user", "プレイヤー戦績", "プロフィール", ApiGame.ZZZ, ApiScope.RECORD, "index"),
        r("get_zzz_agents", "エージェント一覧", "キャラクター", ApiGame.ZZZ, ApiScope.RECORD, "avatar/basic"),
        r("get_bangboos", "ボンプ一覧", "キャラクター", ApiGame.ZZZ, ApiScope.RECORD, "buddy/info"),
        r("get_zzz_agent_info", "エージェント装備・詳細", "キャラクター", ApiGame.ZZZ, ApiScope.RECORD, "avatar/info"),
        r("get_zzz_upgrade_guide_agents", "育成対象のエージェント", "育成", ApiGame.ZZZ, ApiScope.CULTIVATE, "user/avatar_basic_list", requiresBadge = true),
        r("get_all_zzz_agent_upgrade_guides", "全エージェント育成ガイド", "育成", ApiGame.ZZZ, ApiScope.CULTIVATE, "user/batch_avatar_detail_v2", requiresBadge = true),

        r("get_zzz_agent_upgrade_guide", "エージェント育成ガイド", "育成", ApiGame.ZZZ, ApiScope.CULTIVATE, "user/batch_avatar_detail_v2", "READ_POST", required = setOf("avatar_list"), requiresBadge = true),
        r("get_shiyu_defense", "式輿防衛", "戦闘記録", ApiGame.ZZZ, ApiScope.RECORD, "hadal_info_v2", defaults = mapOf("schedule_type" to "1")),
        r("get_deadly_assault", "危局強襲戦", "戦闘記録", ApiGame.ZZZ, ApiScope.RECORD, "hadal_mem_detail_v2", defaults = mapOf("schedule_type" to "1"), useUidParam = true),
        r("get_annihilation_simulacrum", "殲滅シミュレーション", "戦闘記録", ApiGame.ZZZ, ApiScope.RECORD, "holo_boss_detail", defaults = mapOf("schedule_type" to "1"), useUidParam = true),
        r("get_lost_void_summary", "零号ホロウ", "戦闘記録", ApiGame.ZZZ, ApiScope.RECORD, "abysss2_abstract", useUidParam = true),
        r("get_threshold_simulation_brief", "閾値シミュレーション概要", "戦闘記録", ApiGame.ZZZ, ApiScope.RECORD, "void_front_battle_period_abstract_info", defaults = mapOf("schedule_type" to "1"), useUidParam = true),
        r("get_threshold_simulation", "閾値シミュレーション詳細", "戦闘記録", ApiGame.ZZZ, ApiScope.RECORD, "void_front_battle_period_detail", defaults = mapOf("schedule_type" to "1"), useUidParam = true),
        r("chronicle_signal_history", "変調履歴", "ガチャ", ApiGame.ZZZ, ApiScope.RECORD, "gacha_record", defaults = mapOf("end_id" to "0"), useUidParam = true),
        r("get_zzz_event_calendar", "イベントカレンダー", "イベント", ApiGame.ZZZ, ApiScope.RECORD, "activity_calendar", useUidParam = true),
        r("get_zzz_gacha_calendar", "変調カレンダー", "ガチャ", ApiGame.ZZZ, ApiScope.RECORD, "gacha_calendar", useUidParam = true),
        r("get_zzz_gacha_info", "現在の変調情報", "ガチャ", ApiGame.ZZZ, ApiScope.RECORD, "cur_gacha_detail", useUidParam = true),

        r("get_honkai_user", "崩壊3rd戦績", "プロフィール", ApiGame.HONKAI, ApiScope.RECORD, "index"),
        r("get_honkai_abyss", "崩壊3rd・深淵", "戦闘記録", ApiGame.HONKAI, ApiScope.RECORD, "index"),
        r("get_full_honkai_user", "崩壊3rdの全戦績", "プロフィール", ApiGame.HONKAI, ApiScope.RECORD, "index"),

        r("get_honkai_battlesuits", "戦乙女", "キャラクター", ApiGame.HONKAI, ApiScope.RECORD, "characters"),
        r("get_honkai_old_abyss", "量子多様体", "戦闘記録", ApiGame.HONKAI, ApiScope.RECORD, "latestOldAbyssReport"),
        r("get_honkai_superstring_abyss", "超弦空間", "戦闘記録", ApiGame.HONKAI, ApiScope.RECORD, "newAbyssReport"),
        r("get_honkai_elysian_realm", "往世楽土", "戦闘記録", ApiGame.HONKAI, ApiScope.RECORD, "godWar"),
        r("get_honkai_memorial_arena", "記憶戦場", "戦闘記録", ApiGame.HONKAI, ApiScope.RECORD, "battleFieldReport"),
        r("get_honkai_notes", "リアルタイムノート", "デイリー", ApiGame.HONKAI, ApiScope.RECORD, "note"),

        r("get_game_accounts", "連携中のゲームアカウント", "HoYoLAB", null, ApiScope.ACCOUNTS, "binding/api/getUserGameRolesByCookie"),
        r("get_record_cards", "ゲームアカウント一覧", "HoYoLAB", null, ApiScope.CARD, "getGameRecordCard"),

        r("get_reward_info", "デイリーチェックイン状況", "HoYoLAB", null, ApiScope.REWARD, "info"),
        r("get_monthly_rewards", "デイリーチェックイン報酬一覧", "HoYoLAB", null, ApiScope.REWARD, "home"),
        r("claimed_rewards", "チェックイン済み報酬", "HoYoLAB", null, ApiScope.REWARD, "award", defaults = mapOf("current_page" to "1")),
        r("get_genshin_diary", "旅人の札記", "月次記録", ApiGame.GENSHIN, ApiScope.LEDGER, "month_info"),
        r("get_starrail_diary", "開拓月次記録", "月次記録", ApiGame.STARRAIL, ApiScope.LEDGER, "month_info"),
        r("genshin_diary_log", "原神通貨の明細", "月次記録", ApiGame.GENSHIN, ApiScope.LEDGER, "month_detail", defaults = mapOf("type" to "1", "current_page" to "1", "page_size" to "100")),
        r("starrail_diary_log", "スタレ通貨の明細", "月次記録", ApiGame.STARRAIL, ApiScope.LEDGER, "month_detail", defaults = mapOf("type" to "1", "current_page" to "1", "page_size" to "100")),

        r("get_calculator_characters", "育成計算・キャラクター一覧", "育成計算", ApiGame.GENSHIN, ApiScope.CALCULATOR, "v2/avatar/list", "READ_POST", defaults = mapOf("element_attr_ids" to "[]", "weapon_cat_ids" to "[]", "page" to "1", "size" to "200")),
        r("get_calculator_weapons", "育成計算・武器一覧", "育成計算", ApiGame.GENSHIN, ApiScope.CALCULATOR, "v2/weapon/list", "READ_POST", defaults = mapOf("page" to "1", "size" to "200")),
        r("get_calculator_artifacts", "育成計算・聖遺物一覧", "育成計算", ApiGame.GENSHIN, ApiScope.CALCULATOR, "v2/reliquary/list", "READ_POST", defaults = mapOf("page" to "1", "size" to "200", "reliquary_cat_id" to "1")),
        r("get_calculator_furnishings", "育成計算・調度品一覧", "育成計算", ApiGame.GENSHIN, ApiScope.CALCULATOR, "v2/furniture/list", "READ_POST", defaults = mapOf("page" to "1", "size" to "200")),
        r("get_character_details", "育成計算・キャラ詳細", "育成計算", ApiGame.GENSHIN, ApiScope.CALCULATOR, "v2/sync/avatar/detail", required = setOf("avatar_id")),
        r("get_complete_artifact_set", "聖遺物セット", "育成計算", ApiGame.GENSHIN, ApiScope.CALCULATOR, "v2/reliquary/set", required = setOf("reliquary_id")),
        r("get_teapot_replica_blueprint", "調度品設計図", "育成計算", ApiGame.GENSHIN, ApiScope.CALCULATOR, "v2/furniture/blueprint", required = setOf("share_code")),

        r("calculate_enhancement", "育成素材を計算", "育成計算", ApiGame.GENSHIN, ApiScope.CALCULATOR, "v2/compute", "READ_POST"),
        r("calculate_batch_enhancement", "まとめて育成素材を計算", "育成計算", ApiGame.GENSHIN, ApiScope.CALCULATOR, "v3/batch_compute", "READ_POST", required = setOf("items")),
        r("calculate_furnishings", "調度品の素材を計算", "育成計算", ApiGame.GENSHIN, ApiScope.CALCULATOR, "v1/furniture/compute", "READ_POST", required = setOf("list")),
        r("get_owned_calculator_characters", "所持キャラクターの育成状況", "育成計算", ApiGame.GENSHIN, ApiScope.CALCULATOR, "v2/sync/avatar/list", "READ_POST", defaults = mapOf("uid" to "{uid}", "region" to "{server}", "page" to "1", "size" to "200")),

        r("get_lineup_fields", "編成シミュレーター設定", "編成", ApiGame.GENSHIN, ApiScope.LINEUP, "config"),
        r("get_lineup_scenarios", "編成シナリオ", "編成", ApiGame.GENSHIN, ApiScope.LINEUP, "tags"),
        r("get_lineups", "編成一覧", "編成", ApiGame.GENSHIN, ApiScope.LINEUP, "lineup/index", defaults = mapOf("next_page_token" to "", "limit" to "", "tag_id" to "", "order" to "Hot", "roles" to "")),
        r("get_lineup_details", "編成詳細", "編成", ApiGame.GENSHIN, ApiScope.LINEUP, "lineup/detail", required = setOf("id")),
        r("get_user_lineups", "マイ編成", "編成", ApiGame.GENSHIN, ApiScope.LINEUP, "user/lineup", defaults = mapOf("limit" to "100")),
        r("get_favorite_lineups", "お気に入り編成", "編成", ApiGame.GENSHIN, ApiScope.LINEUP, "user/favour_lineup", defaults = mapOf("limit" to "100")),
        r("get_lineup_character_history", "編成履歴", "編成", ApiGame.GENSHIN, ApiScope.LINEUP, "lineup/history", defaults = mapOf("limit" to "100")),

        r("get_tcg_deck_labels", "デッキラベル", "七聖召喚", ApiGame.GENSHIN, ApiScope.CARD_PLAZA, "labels"),
        r("get_tcg_cards", "カード一覧", "七聖召喚", ApiGame.GENSHIN, ApiScope.CARD_PLAZA, "roles"),
        r("get_tcg_card_effect", "カード効果", "七聖召喚", ApiGame.GENSHIN, ApiScope.CARD_PLAZA, "role/skill", required = setOf("id")),
        r("get_tcg_decks", "公開デッキ", "七聖召喚", ApiGame.GENSHIN, ApiScope.CARD_PLAZA, "index", "READ_POST", defaults = mapOf("next_page_token" to "", "keywords" to "", "label_id" to "1", "order" to "O_HOT", "role_card_id" to "[]", "page_size" to "20")),
        r("get_tcg_deck", "デッキ詳細", "七聖召喚", ApiGame.GENSHIN, ApiScope.CARD_PLAZA, "detail", required = setOf("id")),
        r("get_tcg_deck_comments", "デッキコメント", "七聖召喚", ApiGame.GENSHIN, ApiScope.BOULEUTERION, "account/reply/list", defaults = mapOf("app_id" to "10034", "game_biz" to "hk4e", "next" to "0", "mode" to "3"), required = setOf("entity_key")),

        r("teapot_replicas", "塵歌壺レプリカ", "塵歌壺", ApiGame.GENSHIN, ApiScope.TEAPOT, "list", defaults = mapOf("page" to "1", "page_size" to "20", "zip_type" to "1", "block_id" to "", "module_id" to "", "target_region" to "", "version" to "")),
        r("get_wiki_previews", "Wiki記事一覧", "Wiki", null, ApiScope.WIKI, "get_entry_page_list", "READ_POST", defaults = mapOf("page_num" to "1", "page_size" to "1000", "filters" to "[]", "use_es" to "true"), required = setOf("menu_id")),
        r("get_wiki_page", "Wiki記事", "Wiki", null, ApiScope.WIKI, "entry_page", required = setOf("entry_page_id")),
        r("get_wiki_pages", "複数Wiki記事", "Wiki", null, ApiScope.WIKI, "entry_pages", "READ_POST", required = setOf("entry_page_ids")),

        r("get_starrail_lineup_game_modes", "スタレ編成モード", "編成", ApiGame.STARRAIL, ApiScope.HSR_LINEUP, "tag"),
        r("get_starrail_lineups", "スタレ編成一覧", "編成", ApiGame.STARRAIL, ApiScope.HSR_LINEUP, "lineup/index", defaults = mapOf("next_page_token" to "", "order" to "Match", "game" to "hkrpg"), required = setOf("tag_id", "group_id", "type")),
        r("get_starrail_lineup_schedules", "編成シミュレーター開催期間", "編成", ApiGame.STARRAIL, ApiScope.HSR_LINEUP, "schedule/list", defaults = mapOf("type" to "Chasm")),
        r("get_starrail_lineup_floor", "編成シミュレーターの階層", "編成", ApiGame.STARRAIL, ApiScope.HSR_LINEUP, "tag", required = setOf("type", "floor")),
        r("get_genshin_announcements", "原神のお知らせ", "お知らせ", ApiGame.GENSHIN, ApiScope.ANNOUNCEMENT, "announcement/api/getAnnList"),
        r("get_starrail_announcements", "スタレのお知らせ", "お知らせ", ApiGame.STARRAIL, ApiScope.ANNOUNCEMENT, "announcement/api/getAnnList"),
        r("get_zzz_announcements", "ゼンゼロのお知らせ", "お知らせ", ApiGame.ZZZ, ApiScope.ANNOUNCEMENT, "announcement/api/getAnnList"),
        r("get_starrail_warp_brief", "跳躍概要", "ガチャ", ApiGame.STARRAIL, ApiScope.HSR_WARP, "brief", requiresBadge = true),
        r("get_starrail_warp_pool_stats", "跳躍統計", "ガチャ", ApiGame.STARRAIL, ApiScope.HSR_WARP, "pool_stat", required = setOf("gacha_type"), requiresBadge = true),
        r("get_starrail_five_star_warps", "星5跳躍履歴", "ガチャ", ApiGame.STARRAIL, ApiScope.HSR_WARP, "five_star_list", required = setOf("gacha_type"), requiresBadge = true),

        r("wish_history", "祈願履歴", "ガチャ", ApiGame.GENSHIN, ApiScope.GACHA, "getGachaLog", authKeyRequired = true),
        r("mw_wish_history", "幻想の星々の祈願履歴", "ガチャ", ApiGame.GENSHIN, ApiScope.GACHA, "getBeyondGachaLog", authKeyRequired = true),
        r("get_genshin_banner_names", "祈願バナー名一覧", "ガチャ", ApiGame.GENSHIN, ApiScope.GACHA, "getConfigList", authKeyRequired = true),
        r("get_genshin_banner_ids", "祈願バナーID一覧", "ガチャ", ApiGame.GENSHIN, ApiScope.WEBSTATIC, "gacha_info/hk4e/cn_gf01/gacha/list.json"),
        r("get_banner_details", "祈願バナー詳細", "ガチャ", null, ApiScope.WEBSTATIC, "gacha_info/{game}/{server}/{banner_id}/{lang}.json"),
        r("warp_history", "跳躍履歴", "ガチャ", ApiGame.STARRAIL, ApiScope.GACHA, "getGachaLog", authKeyRequired = true),
        r("signal_history", "変調履歴", "ガチャ", ApiGame.ZZZ, ApiScope.GACHA, "getGachaLog", authKeyRequired = true),
        r("transaction_log", "通貨取引明細", "取引履歴", ApiGame.GENSHIN, ApiScope.TRANSACTION, "GetActionLog", authKeyRequired = true),

        r("search_users", "HoYoLABユーザー検索", "HoYoLAB", null, ApiScope.BBS, "community/search/wapi/search/user", required = setOf("keyword"), defaults = mapOf("page_size" to "20")),
        r("get_hoyolab_user", "HoYoLABユーザー", "HoYoLAB", null, ApiScope.BBS, "community/painter/wapi/user/full"),
        r("get_web_events", "HoYoLAB Webイベント", "HoYoLAB", null, ApiScope.BBS, "community/community_contribution/wapi/event/list", defaults = mapOf("size" to "15", "offset" to "")),
        r("get_mimo_games", "Mimo対応ゲーム", "HoYoLAB", null, ApiScope.MIMO, "index"),
        r("get_mimo_point_count", "Mimoポイント残高", "HoYoLAB", null, ApiScope.MIMO, "index"),
        r("get_mimo_tasks", "Mimoタスク", "HoYoLAB", null, ApiScope.MIMO, "task-list", required = setOf("game_id", "version_id")),
        r("get_mimo_shop_items", "Mimoショップ", "HoYoLAB", null, ApiScope.MIMO, "exchange-list", required = setOf("game_id", "version_id")),
        r("get_mimo_lottery_info", "Mimo抽選情報", "HoYoLAB", null, ApiScope.MIMO, "lottery-info", required = setOf("game_id", "version_id")),
        r("get_mimo_point_history", "Mimoポイント履歴", "HoYoLAB", null, ApiScope.MIMO, "point-record", defaults = mapOf("page" to "1", "page_size" to "20"), required = setOf("game_id", "version_id", "operator_type")),
        r("get_accompany_characters", "HoYoLABコンパニオン", "HoYoLAB", null, ApiScope.BBS, "community/painter/api/getChannelRoleList", "READ_POST"),
        r("get_accompany_character_details", "コンパニオン情報", "HoYoLAB", null, ApiScope.BBS, "community/painter/api/topic/info", defaults = mapOf("scene" to "SceneAll"), required = setOf("topic_id")),
        r("get_accompany_character_posts", "コンパニオン投稿", "HoYoLAB", null, ApiScope.BBS, "community/post/api/topic/post/list", "READ_POST", defaults = mapOf("scene" to "1", "list_type" to "hot", "page_size" to "15", "is_first_brush" to "true", "last_id" to "", "client_param" to "", "session_param" to ""), required = setOf("topic_id")),
        r("get_accompany_voice_setting", "ボイス設定", "HoYoLAB", null, ApiScope.BBS, "community/user/api/role_setting/get", "READ_POST", defaults = mapOf("types" to "[\"SettingTypeVoiceScript\"]"), required = setOf("game_id", "role_id"))
    )

    private val byName = methods.associateBy { it.name }

    fun find(name: String): ApiMethod? = byName[name]

    fun forGame(game: Game): List<ApiMethod> {
        val selected = ApiGame.from(game)
        return methods.filter { it.game == selected || it.game == null }
    }
}
