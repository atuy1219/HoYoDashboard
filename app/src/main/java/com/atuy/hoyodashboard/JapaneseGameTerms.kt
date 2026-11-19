package com.atuy.hoyodashboard

import com.atuy.hoyodashboard.api.ApiGame
import com.atuy.hoyodashboard.api.localizeDiarySource
import java.util.Locale

private val networkNamePattern = Regex("([a-z0-9])([A-Z])")
private val digits = Regex("\\d+")
internal fun japaneseFieldFallback(key: String): String {
    fieldGlossary[key]?.let { return it }
    if (key.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }) return key
    val words = networkNamePattern.replace(key, "$1_$2").lowercase(Locale.ROOT).split('_')
    val translated = ArrayList<String>()
    var index = 0
    while (index < words.size) {
        val pair = if (index + 1 < words.size) fieldWords[words[index] + "_" + words[index + 1]] else null
        if (pair != null) {
            translated += pair
            index += 2
        } else {
            val word = words[index++]
            fieldWords[word]?.let { translated += it } ?: if (digits.matches(word)) translated.add(word) else Unit
        }
    }
    return translated.distinct().joinToString("・").ifBlank { "項目" }
}

private val fieldGlossary = buildMap {
    fun add(label: String, vararg keys: String) = keys.forEach { put(it, label) }

    add("アジア", "asia")
    add("プレイヤー情報", "role_basic_info", "basic_info", "in_game_data", "game_data_show")
    add("アカウント番号", "account_uid", "hoyolab_id", "game_uid", "game_role_id", "user_id")
    add("冒険ランク", "adventure_rank", "level_of_ow")
    add("活動日数", "days_active", "active_days_count")
    add("達成したアチーブメント", "achievement_number", "achievements")
    add("命ノ星座を解放した数", "activation_number", "active_constellation_num")
    add("好感度", "fetter", "friendship")
    add("最大好感度のキャラクター", "max_friendship_characters", "full_fetter_avatar_num")
    add("武器の精錬ランク", "refinement", "affix_level", "affix_rank")
    add("突破段階", "ascension", "promote_level", "promotes")
    add("最大レベル", "max_level", "level_caps", "equip_level_max", "avatar_level_max", "weapon_level_max")
    add("武器の種類", "weapon_type", "weapon_cat_id")
    add("運命", "path", "avatar_base_type", "base_type")
    add("心象映画", "mindscape")
    add("特性", "specialty", "avatar_profession", "profession")
    add("陣営", "camp_name_mi18n", "faction_name")
    add("戦闘スキル", "skill_items", "skill_cost", "normal_skill_rank", "skill_normal_level_max")
    add("終結スキル", "energy_skill_rank")
    add("コアスキル", "skill_core_level_max", "core_skill")
    add("潜在能力", "potential", "awaken_info", "awaken_simple_info")
    add("聖痕", "stigmata", "stigmatas")
    add("武装人形", "elf", "elf_list")
    add("着せ替え", "costumes", "outfits", "wearing")
    add("編成の履歴", "lineup_history")
    add("おすすめ編成", "lineup_recommend", "lineup_recommendation")
    add("おすすめステータス", "recommend_property", "recommend_relic_properties", "target_stats")
    add("会心率", "crit", "critical_rate")
    add("攻撃時の追加効果", "weapon_affix")
    add("最大ダメージ", "max_damage_avatar", "max_damage_character", "dps")
    add("最多撃破", "max_defeat_avatar", "max_defeat_character", "most_kills")
    add("最多被ダメージ", "max_take_damage_avatar", "max_take_damage_character", "most_damage_taken")
    add("最多スキル使用", "most_skills_used", "skill_use_num")
    add("最多出場", "most_played")
    add("突破済みの部屋", "chambers", "rooms_cleared")
    add("第1チームの敵", "first_half_enemies", "top_half_floor_monster")
    add("第2チームの敵", "second_half_enemies", "bottom_half_floor_monster")
    add("第1ウェーブ", "node_1")
    add("第2ウェーブ", "node_2")
    add("第3ウェーブ", "node_3")
    add("追加バフ", "addition_buff", "addition_buff_1", "addition_buff_2")
    add("バフ", "buff", "buffs", "buff_list", "default_buffs")
    add("クリアした階層", "floors_cleared", "highest_floor", "max_layer", "max_floor_id")
    add("難易度", "difficulty", "difficulty_id", "highest_difficulty", "hard_mode", "hard_mode_name_mi18n")
    add("最大スコア", "highest_score", "room_max_score", "total_max_score")
    add("ステージのスコア", "stage_score", "challenge_score")
    add("順位", "ranking", "placement", "battle_field_rank", "defeat_rank")
    add("上位割合", "rank_percent", "hard_rank_percent", "ranking_percentage", "battle_field_ranking_percentage")
    add("クリア回数", "total_clears", "finish_cnt", "total_win_times", "total_wins")
    add("編成メンバー", "members", "main_avatars", "main_characters", "all_avatar")
    add("控えメンバー", "backup_avatar_list", "backup_avatars", "backup_characters")
    add("サポートメンバー", "support_avatars", "support_characters", "player_assists")
    add("残り時間", "remaining_time", "time_remaining", "countdown_seconds", "left_ts", "remain_time")
    add("回復までの時間", "restore", "resin_recovery_time", "stamina_recover_time", "seconds_till_full")
    add("対戦日時", "match_time", "challenge_time", "floor_challenge_time", "challenge_time_node2")
    add("開始日時", "start_date_time", "start_datetime", "start_at", "start_ts", "hadal_begin_time")
    add("終了日時", "end_date_time", "end_datetime", "end_at", "end_ts", "hadal_end_time")
    add("達成日時", "completed_at", "finish_date_time", "finish_datetime")
    add("更新日時", "last_update_time", "synced_at", "last_edited_at")
    add("天然樹脂", "resin", "current_resin")
    add("天然樹脂の上限", "max_resin")
    add("洞天宝銭", "current_home_coin", "current_realm_currency")
    add("洞天宝銭の上限", "max_home_coin", "max_realm_currency")
    add("派遣の進行状況", "expedition_state", "expeditions", "accepted_expedition_num", "accepted_epedition_num")
    add("参量物質変化器", "transformer")
    add("デイリー任務", "daily_task", "commission_progress")
    add("達成済み", "finished", "done", "finished_num", "completed_tasks", "completed_commissions")
    add("報酬を受取済み", "claimed_rewards", "is_extra_task_reward_received", "claimed_commission_reward")
    add("開拓力", "current_stamina", "stamina")
    add("開拓力の上限", "max_stamina")
    add("予備開拓力", "current_reserve_stamina", "reserve_stamina")
    add("デイリー訓練", "current_train_score", "max_train_score")
    add("バッテリー", "energy", "battery_charge")
    add("活躍度", "vitality", "engagement")
    add("スクラッチ", "card_sign", "card_sign_state", "scratch_card_completed")
    add("ビデオ屋", "vhs_sale", "video_store_state", "sale_state")
    add("零号ホロウ", "hollow_zero")
    add("調査ポイント", "survey_points", "investigation_point")
    add("懸賞依頼", "bounty_commission")
    add("週間任務", "weekly_task")
    add("会員カード", "member_card", "member_card_state")
    add("所持しているキャラクター", "agents", "character_num")
    add("ボンプ", "bangboo", "bangboos", "buddy", "buddies", "buddy_list")
    add("ボンプの数", "buddy_num", "bangboo_obtained")
    add("ボンプチケット", "boopon", "boopons")
    add("武器・音動機", "weapon_list", "all_weapon", "weapon_banner")
    add("獲得した星5アイテム", "warps", "character_warps", "light_cone_warps")
    add("跳躍の回数", "gacha_count", "warp_count")
    add("最後の星5からの回数", "pity")
    add("ピックアップ獲得数", "up_count")
    add("ピックアップ対象", "up_item", "is_up")
    add("祈願・跳躍期間", "banners", "banner_type", "character_banners", "weapon_banners", "chronicled_banners")
    add("変調の情報", "gacha_info_list", "avatar_gacha_schedule_list", "weapon_gacha_schedule_list")
    add("開催状況", "gacha_state", "pool_status", "is_now", "is_show")
    add("宝箱の探索", "treasure_chests", "chest_num", "chest_number")
    add("普通の宝箱", "common_chests", "common_chest_number")
    add("精巧な宝箱", "exquisite_chests", "exquisite_chest_number")
    add("貴重な宝箱", "precious_chests", "precious_chest_number")
    add("豪華な宝箱", "luxurious_chests", "luxurious_chest_number")
    add("珍奇な宝箱", "remarkable_chests", "magic_chest_number")
    add("風神の瞳", "anemoculi", "anemoculus_number")
    add("岩神の瞳", "geoculi", "geoculus_number")
    add("雷神の瞳", "electroculi", "electroculus_number")
    add("草神の瞳", "dendroculi", "dendroculus_number")
    add("水神の瞳", "hydroculi", "hydroculus_number")
    add("炎神の瞳", "pyroculi", "pyroculus_number")
    add("探索度", "explore_percent", "exploration_percentage", "explored_percentage")
    add("世界探索", "world_explorations", "explorations", "world")
    add("ワープポイント", "waypoints", "way_point_number", "unlocked_waypoints")
    add("秘境", "trounce_domains", "domains", "domain", "unlocked_domains", "dungeon_name")
    add("奉納", "offerings")
    add("地域の評判", "reputation", "natlan_reputation", "natan_reputation")
    add("塵歌壺", "teapot", "realm_exploration", "realms")
    add("洞天仙力", "comfort_num", "comfort_level_name", "comfort")
    add("魔神任務", "archon_quest_progress", "is_open_archon_quest", "archon_quest_unlocked")
    add("開拓任務", "mainlines_finished", "mainline", "is_finish_all_mainline")
    add("開拓クエスト・幕間", "interchapters_finished", "is_finish_all_interchapter")
    add("ランクに必要な戦績", "abyss_duty", "abyss_task", "abyss_collect")
    add("深境螺旋・深淵", "abyss", "abyss_detail", "abyss_level", "abyss_process")
    add("往世楽土", "god_war")
    add("記憶戦場", "battle_field")
    add("超弦空間", "ultra_endless")
    add("量子多様体", "greedy_endless")
    add("使用した刻印", "signets")
    add("相手プレイヤー", "opponent", "opposite")
    add("勝敗", "is_win")
    add("対戦数", "total_battles", "total_battle_num", "total_battle_times", "battle_num")
    add("対戦履歴", "recent_matches", "recent_record")
    add("キャラカード", "character_cards", "avatar_card_num_gained", "avatar_card_num_total", "total_character_cards")
    add("アクションカード", "action_card_num_gained", "action_card_num_total", "total_action_cards")
    add("必要な元素サイコロ", "costs", "cost_type", "cost_value", "cost_types", "action_cost")
    add("デッキコード", "card_code")
    add("いいね数", "likes", "like_count", "like_num", "like_cnt")
    add("お気に入り数", "favorites", "favour_cnt", "saves")
    add("閲覧数", "views", "view_num")
    add("投稿", "posts", "post_num", "post")
    add("投稿者", "author", "author_nickname", "recent_posters")
    add("コメント", "comments", "replies", "sub_reply_list", "comment_cnt", "reply_num", "reply_count")
    add("コンパニオン情報", "accompany_info")
    add("一緒に過ごした日数", "accompany_days", "accompany_quarter_days")
    add("コンパニオンポイント", "accompany_point", "accompany_points", "available_accompany_point")
    add("ボイスの言語", "voice_lang", "voice_language")
    add("テキストの言語", "script_lang", "script_language")
    add("テキスト", "script", "voice_scripts")
    add("プロフィール設定", "data_switches", "settings")
    add("所持状況", "is_owned", "unlocked", "has_got", "collected")
    add("就任状況", "enabled", "can_accompany", "can_use")
    add("対象月", "month_data", "month_options", "optional_month", "current_month")
    add("獲得通貨", "currencies", "currency_name", "data_name")
    add("獲得元の内訳", "income_components", "polychrome_incomes", "group_by")
    add("内訳の割合", "percent", "percentage")
    add("星玉", "current_hcoin", "current_stellar_jades")
    add("星軌チケット", "current_rails_pass")
    add("原石", "current_primogems")
    add("モラ", "current_mora")
    add("入手可能な素材", "drop_materials", "available_materials")
    add("素材の入手先", "material_source", "monster_map_url")
    add("入手できる曜日", "drop_day", "weekdays")
    add("調度品", "furnishings")
    add("2セットの効果", "two_piece_description")
    add("4セットの効果", "four_piece_description")
    add("リンク", "link", "links", "url", "web_url", "web_path", "jump_url", "jump_link", "link_url", "target_url")
    add("攻略Wiki", "wiki", "wiki_url", "card_wiki", "avatar_wiki", "relic_wiki", "equip_wiki")
    add("画像", "image_url", "img_url", "header_img_url", "banner_url", "origin_url")
    add("動画", "video_url", "video_list", "videos")
    add("音声", "voice_url", "audio_url", "voice_list")
    add("本文", "html", "rich_text", "richText")
    add("基本情報", "baseInfo")
    add("セット効果", "reliquary_set_effect")
    add("コンポーネント", "component_id", "component_type")
    add("記事", "entry_page", "entry_pages")
    add("開催中", "is_active")
    add("未読", "unread")
}

private val fieldWords = mapOf(
    "avatar" to "キャラクター", "character" to "キャラクター", "agent" to "エージェント", "role" to "キャラクター",
    "weapon" to "武器", "equipment" to "装備", "relic" to "遺物", "artifact" to "聖遺物", "reliquary" to "聖遺物",
    "talent" to "天賦", "skill" to "スキル", "stat" to "ステータス", "property" to "ステータス",
    "attributes" to "ステータス", "ability" to "能力", "append" to "追加", "add" to "追加", "base" to "基礎",
    "checked" to "確認済み", "claim" to "受取", "reward" to "報酬", "award" to "報酬", "drop" to "獲得",
    "item" to "アイテム", "material" to "素材", "point" to "ポイント", "points" to "ポイント", "money" to "通貨",
    "currency" to "通貨", "life" to "体力", "strength" to "強さ", "energy" to "エネルギー", "recovery" to "回復",
    "time" to "時間", "date" to "日付", "datetime" to "日時", "seconds" to "秒", "day" to "日",
    "hour" to "時", "minute" to "分", "second" to "秒", "year" to "年", "month" to "月", "week" to "週",
    "total" to "合計", "cur" to "現在", "current" to "現在", "max" to "最大", "min" to "最小",
    "last" to "前回", "previous" to "前回", "best" to "最高", "next" to "次回", "remaining" to "残り",
    "description" to "説明", "desc" to "説明", "name" to "名称", "title" to "見出し", "subtitle" to "副題",
    "list" to "一覧", "group" to "グループ", "detail" to "詳細", "result" to "結果", "overview" to "概要",
    "version" to "バージョン", "type" to "種類", "state" to "状態", "status" to "状態",
    "stage" to "ステージ", "node" to "ステージ", "schedule" to "開催期間", "season" to "シーズン",
    "rank" to "ランク", "score" to "スコア", "level" to "レベル", "floor" to "階層", "layer" to "階層",
    "room" to "部屋", "round" to "ラウンド", "run" to "挑戦", "challenge" to "挑戦", "battle" to "戦闘",
    "enemy" to "敵", "boss" to "ボス", "monster" to "敵", "buff" to "バフ", "team" to "編成",
    "count" to "件数", "num" to "数", "number" to "数", "size" to "件数", "id" to "番号",
    "icon" to "アイコン", "image" to "画像", "picture" to "画像", "background" to "背景", "card" to "カード",
    "cover" to "表紙", "banner" to "開催情報", "video" to "動画", "voice" to "ボイス", "logo" to "ロゴ",
    "height" to "高さ", "width" to "幅", "color" to "色", "book" to "書籍", "page" to "ページ",
    "url" to "リンク", "link" to "リンク", "wiki" to "攻略Wiki", "target" to "対象", "jump" to "移動先",
    "content" to "内容", "text" to "テキスト", "html" to "本文", "component" to "コンポーネント",
    "module" to "項目", "header" to "見出し", "footer" to "末尾", "button" to "ボタン", "label" to "分類",
    "uid" to "ユーザーID", "game" to "ゲーム", "user" to "プレイヤー", "account" to "アカウント",
    "tag" to "タグ", "setting" to "設定", "profile" to "プロフィール", "filter" to "絞り込み",
    "chapter" to "章", "story" to "ストーリー", "event" to "イベント", "activity" to "イベント",
    "source" to "入手先", "reason" to "理由", "action" to "操作", "record" to "記録", "history" to "履歴",
    "server" to "サーバー", "region" to "地域", "platform" to "プラットフォーム",
    "hard" to "高難度", "easy" to "初級", "normal" to "通常", "special" to "特別", "bonus" to "ボーナス",
    "clear" to "クリア", "finish" to "完了", "end" to "終了", "begin" to "開始", "start" to "開始",
    "lock" to "ロック", "unlock" to "解放", "unlocked" to "解放済み", "enable" to "有効",
    "show" to "表示", "visibility" to "公開設定", "public" to "公開", "visible" to "表示",
    "prob" to "確率", "rate" to "割合", "percent" to "割合", "damage" to "ダメージ", "heal" to "回復",
    "attack" to "攻撃", "defense" to "防御", "life" to "体力", "hp" to "HP", "element" to "属性",
    "effect" to "効果", "crit" to "会心", "rare" to "レア", "rarity" to "レア度", "star" to "星",
    "stock" to "在庫", "sell" to "販売", "shop" to "ショップ", "limit" to "上限",
    "up" to "ピックアップ", "custom" to "カスタム", "data" to "情報", "info" to "情報",
    "wishlist" to "希望リスト", "quest" to "任務", "task" to "タスク", "refresh" to "更新",
    "value" to "値", "fraction" to "割合", "progress" to "進捗", "level_current" to "現在レベル",
    "crit_rate" to "会心率", "crit_dmg" to "会心ダメージ", "lineup" to "編成", "build" to "育成",
    "formula" to "レシピ", "suit" to "セット", "set" to "セット"
)

private val languageNames = mapOf(
    "ja" to "日本語", "ja-jp" to "日本語", "jp" to "日本語", "en" to "英語", "en-us" to "英語",
    "zh" to "中国語", "zh-cn" to "中国語（簡体字）", "zh-tw" to "中国語（繁体字）",
    "ko" to "韓国語", "ko-kr" to "韓国語", "de-de" to "ドイツ語", "fr-fr" to "フランス語",
    "es-es" to "スペイン語", "pt-pt" to "ポルトガル語", "ru-ru" to "ロシア語", "th-th" to "タイ語",
    "id-id" to "インドネシア語", "vi-vn" to "ベトナム語", "it-it" to "イタリア語", "tr-tr" to "トルコ語"
)

private val serverNames = mapOf(
    "os_usa" to "北米", "prod_official_usa" to "北米", "prod_gf_us" to "北米", "usa01" to "北米",
    "os_euro" to "欧州", "prod_official_eur" to "欧州", "prod_gf_eu" to "欧州", "eur01" to "欧州",
    "os_asia" to "アジア", "prod_official_asia" to "アジア", "prod_gf_jp" to "アジア",
    "prod_gf_sg" to "東南アジア", "overseas01" to "東南アジア",
    "os_cht" to "台湾・香港・マカオ", "prod_official_cht" to "台湾・香港・マカオ",
    "cn_gf01" to "中国本土・公式", "prod_gf_cn" to "中国本土・公式", "cn01" to "中国本土",
    "cn_qd01" to "中国本土・提携", "prod_qd_cn" to "中国本土・提携",
    "asia" to "アジア", "america" to "北米", "europe" to "欧州", "global" to "グローバル"
)

private val namedValues = mapOf(
    "SaleStateDone" to "売上を受取可能", "SaleStateNo" to "開店前", "SaleStateDoing" to "営業中",
    "CardSignDone" to "完了", "CardSignNo" to "未完了", "CardSignNotDone" to "未完了",
    "BenchStateCanProduce" to "製作可能", "BenchStateProducing" to "製作中", "BenchStateUnknown" to "状況不明",
    "ShelveStateCanSell" to "陳列可能", "ShelveStateSelling" to "販売中", "ShelveStateSoldOut" to "売り切れ",
    "ShelveStateUnknown" to "状況不明", "Finished" to "完了", "Ongoing" to "進行中",
    "Dispatched" to "派遣中", "Claimed" to "受取済み", "Unfinished" to "未完了",
    "GachaType_Standard" to "恒常跳躍", "GachaType_Newbie" to "始発跳躍",
    "GachaType_AvatarUp" to "キャラクターイベント跳躍", "GachaType_EquipmentUp" to "光円錐イベント跳躍",
    "GachaType_CollabAvatarUp" to "コラボキャラクター跳躍", "GachaType_CollabEquipmentUp" to "コラボ光円錐跳躍",
    "GACHA_TYPE_PERMANENT" to "常設チャンネル", "GACHA_TYPE_CHARACTER_UP" to "独占チャンネル",
    "GACHA_TYPE_WEAPON_UP" to "音動機チャンネル", "GACHA_TYPE_BANGBOO" to "ボンプチャンネル",
    "GACHA_TYPE_CHARACTER_RETURN" to "復刻エージェント変調", "GACHA_TYPE_WEAPON_RETURN" to "復刻音動機変調",
    "Chasm" to "忘却の庭", "Story" to "虚構叙事", "Boss" to "末日の幻影", "Peak" to "異相の仲裁",
    "Hot" to "人気順", "CreatedTime" to "新着順", "Match" to "所持キャラクターとの一致順",
    "O_HOT" to "人気順", "O_LATEST" to "新着順",
    "PolychromesData" to "ポリクローム", "MatserTapeData" to "マスターテープ",
    "MasterTapeData" to "マスターテープ", "BooponsData" to "ボンプチケット",
    "primogem" to "原石", "crystal" to "創世結晶", "resin" to "天然樹脂", "artifact" to "聖遺物",
    "weapon" to "武器", "avatar" to "キャラクター", "character" to "キャラクター",
    "LightCone" to "光円錐", "Sword" to "片手剣", "Claymore" to "両手剣", "Polearm" to "長柄武器",
    "Bow" to "弓", "Catalyst" to "法器",
    "Robust" to "存護", "Warrior" to "壊滅", "Rogue" to "巡狩", "Mage" to "知恵",
    "Shaman" to "調和", "Warlock" to "虚無", "Priest" to "豊穣", "Memory" to "記憶", "Elation" to "愉悦",
    "Fire" to "炎", "Ice" to "氷", "Physical" to "物理", "Thunder" to "雷", "Wind" to "風",
    "Quantum" to "量子", "Imaginary" to "虚数", "Ether" to "エーテル",
    "Pyro" to "炎", "Hydro" to "水", "Anemo" to "風", "Electro" to "雷", "Dendro" to "草", "Cryo" to "氷", "Geo" to "岩",
    "LINK" to "リンク", "IMAGE" to "画像", "TEXT" to "テキスト", "VIDEO" to "動画",
    "AUDIO" to "音声", "HTML" to "本文", "TABLE" to "表", "RICH_TEXT" to "本文",
    "BUTTON" to "ボタン", "NORMAL" to "通常", "UNKNOWN" to "不明", "NONE" to "なし",
    "SUCCESS" to "成功", "FAILED" to "失敗", "LOCKED" to "未解放", "UNLOCKED" to "解放済み",
    "FINISHED" to "完了", "UNFINISHED" to "未完了", "IN_PROGRESS" to "進行中",
    "AVAILABLE" to "利用可能", "UNAVAILABLE" to "利用不可", "COMPLETED" to "完了",
    "true" to "はい", "false" to "いいえ", "yes" to "はい", "no" to "いいえ",
    "Genshin Impact" to "原神", "Honkai: Star Rail" to "崩壊：スターレイル",
    "Honkai Impact 3rd" to "崩壊3rd", "Zenless Zone Zero" to "ゼンレスゾーンゼロ",
    "Tears of Themis" to "未定事件簿"
)

private val lowercaseNamedValues = namedValues.mapKeys { it.key.lowercase(Locale.ROOT) }
private fun namedValue(value: String): String? = namedValues[value] ?: lowercaseNamedValues[value.lowercase(Locale.ROOT)]
private val machineValue = Regex("[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+|[a-zA-Z0-9]+(?:_[a-zA-Z0-9]+){2,}")
private val stateKeys = setOf("state", "status", "gacha_state", "video_store_state", "sale_state", "shelve_state",
    "bench_state", "member_card_state", "expedition_state", "card_sign_state", "card_sign")

internal fun localizedApiValue(key: String, value: String, method: String?): String? {
    val game = when {
        method?.contains("zzz") == true || method in setOf("signal_history", "chronicle_signal_history") -> ApiGame.ZZZ
        method?.contains("starrail") == true || method == "warp_history" -> ApiGame.STARRAIL
        method?.contains("genshin") == true || method in setOf("wish_history", "mw_wish_history") -> ApiGame.GENSHIN
        else -> null
    }
    if (key == "action" || key == "source") {
        localizeDiarySource(value).takeIf { it != value }?.let { return it }
    }
    return when (key) {
        "game_id", "gids" -> mapOf("1" to "崩壊3rd", "2" to "原神", "4" to "未定事件簿",
            "5" to "HoYoLAB", "6" to "崩壊：スターレイル", "8" to "ゼンレスゾーンゼロ")[value]
        "game_biz" -> when (value.substringBefore('_')) {
            "bh3" -> "崩壊3rd"
            "hk4e" -> "原神"
            "nxx" -> "未定事件簿"
            "hkrpg" -> "崩壊：スターレイル"
            "nap" -> "ゼンレスゾーンゼロ"
            else -> null
        }
        "region", "server", "region_name", "server_name" -> serverNames[value.lowercase(Locale.ROOT)]
        "voice_lang", "voice_language", "script_lang", "script_language" -> languageNames[value.lowercase(Locale.ROOT)]
        "gacha_type", "op_gacha_type", "banner_type" -> value.toIntOrNull()?.let { id -> game?.let { bannerLabel(it, id) } } ?: namedValues[value]
        "card_type" -> mapOf("0" to "キャラカード", "2" to "装備カード", "5" to "イベントカード", "6" to "支援カード")[value]
        "path", "base_type", "avatar_base_type" -> mapOf("1" to "壊滅", "2" to "巡狩",
            "3" to "知恵", "4" to "調和", "5" to "虚無", "6" to "存護", "7" to "豊穣",
            "8" to "記憶", "9" to "愉悦")[value] ?: namedValues[value]
        "avatar_profession", "profession", "specialty" -> mapOf("1" to "強攻", "2" to "撃破", "3" to "異常",
            "4" to "支援", "5" to "防護", "6" to "命破", "7" to "装甲")[value]
        "element_type", "avatar_element_type", "element_attr_id" -> mapOf(
            "200" to "物理", "201" to "炎", "202" to "氷", "203" to "電気", "204" to "風", "205" to "エーテル",
            "300" to "光"
        )[value] ?: namedValues[value]
        "operator_type" -> mapOf("1" to "獲得", "2" to "消費")[value]
        "schedule_type" -> mapOf("1" to "今期", "2" to "前期", "3" to "直近の開催分")[value]
        "month", "data_month", "current_month" -> localizedMonth(value)
        "is_up", "is_owned", "is_unlock", "is_sign" -> mapOf("1" to "はい", "0" to "いいえ")[value]
        "kind", "type", "data_type", "item_type", "state", "status", "element", "root_type", "lineup_type", "order",
        "component_type", "component_id", "content_type", "game", "game_name", "weapon_type", "element_type_name" ->
            namedValue(value)
        else -> namedValue(value).takeIf { key in stateKeys || key == "action" || key == "currency_type" }
    } ?: if (key in stateKeys && machineValue.matches(value)) "状況不明" else null
}

internal fun gameServerName(server: String): String = serverNames[server.lowercase(Locale.ROOT)] ?: "サーバー不明"
