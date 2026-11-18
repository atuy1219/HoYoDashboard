package com.atuy.hoyodashboard

import android.text.Html
import com.atuy.hoyodashboard.api.ApiGame
import java.util.Locale

internal fun displayText(value: String): String = if ('<' in value || '&' in value) {
    Html.fromHtml(value.replace(Regex("(?is)<(style|script)\\b[^>]*>.*?</\\1>"), ""), Html.FROM_HTML_MODE_COMPACT)
        .toString().trim()
} else value

internal fun argumentLabel(key: String): String = when (key) {
    "id" -> "番号"
    "uid" -> "ユーザーID"
    "post_id" -> "投稿ID"
    "reply_id" -> "返信ID"
    "topic_id" -> "トピックID"
    "role_id" -> "コンパニオンID"
    "entry_page_id" -> "記事ID"
    "entry_page_ids" -> "記事ID（カンマ区切り）"
    "menu_id" -> "記事カテゴリ"
    "avatar_id" -> "キャラクターID"
    "character_ids" -> "キャラクターID（カンマ区切り）"
    "reliquary_id" -> "聖遺物ID"
    "share_code" -> "洞天コピー番号"
    "banner_ids" -> "ガチャ開催ID（カンマ区切り）"
    "gacha_type" -> "ガチャの種類"
    "game_id" -> "ゲーム"
    "version_id" -> "開催ID"
    "task_id" -> "タスクID"
    "item_id" -> "交換品ID"
    "operator_type" -> "収支"
    "type" -> "種類"
    "floor" -> "階層"
    "group_id" -> "開催ID"
    "tag_id" -> "ステージID"
    "entity_key" -> "デッキの識別番号"
    "month" -> "対象月"
    "keyword", "keywords" -> "検索語"
    "setting_id" -> "設定項目"
    "public" -> "公開"
    "avatar_list" -> "エージェントID（カンマ区切り）"
    "voice_language" -> "音声言語"
    "script_language" -> "テキスト言語"
    "authkey" -> "履歴取得キー"
    else -> fieldLabel(key)
}

internal fun bannerLabel(game: ApiGame, type: Int): String = when (game) {
    ApiGame.GENSHIN -> when (type) {
        100 -> "初心者応援祈願"
        200 -> "通常祈願"
        301 -> "キャラクターイベント祈願"
        400 -> "キャラクターイベント祈願・2"
        302 -> "武器イベント祈願"
        500 -> "集録祈願"
        1000 -> "千星の奇域・通常"
        2000, 20011, 20012, 20021, 20022 -> "千星の奇域・期間限定"
        else -> "祈願"
    }
    ApiGame.STARRAIL -> when (type) {
        1 -> "恒常跳躍"
        2 -> "始発跳躍"
        11 -> "キャラクターイベント跳躍"
        12 -> "光円錐イベント跳躍"
        21 -> "コラボキャラクター跳躍"
        22 -> "コラボ光円錐跳躍"
        else -> "跳躍"
    }
    ApiGame.ZZZ -> when (type) {
        1 -> "常設チャンネル"
        2 -> "独占チャンネル"
        3 -> "音動機チャンネル"
        5 -> "ボンプチャンネル"
        102 -> "復刻エージェント変調"
        103 -> "復刻音動機変調"
        else -> "変調"
    }
    else -> "ガチャ"
}

internal fun fieldLabel(key: String): String = fieldLabels[key] ?: key
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .replace('_', ' ')
    .replaceFirstChar { it.titlecase(Locale.ROOT) }

private val fieldLabels = buildMap {
    fun add(label: String, vararg keys: String) = keys.forEach { put(it, label) }

    add("名称", "name", "title", "name_mi18n", "avatar_name", "role_name", "task_name", "item_name", "monster_name")
    add("説明", "description", "desc", "brief", "summary", "simple_desc", "sub_title", "intro")
    add("内容", "content", "text", "window_text", "official")
    add("番号", "id", "entry_page_id", "lineup_id", "deck_id", "task_id", "award_id", "topic_id", "post_id", "reply_id", "entity_key")
    add("レベル", "level", "avatar_level", "level_current")
    add("目標レベル", "level_target")
    add("レア度", "rarity", "rank_type", "quality", "weapon_level", "reliquary_level")
    add("ランク", "rank", "rank_name", "world_level", "level_name", "rating")
    add("命ノ星座", "actived_constellation_num", "constellation", "constellations")
    add("星魂", "eidolons", "ex_ranks", "ranks")
    add("元素", "element", "elements", "element_attr_id", "element_type")
    add("属性", "element_type_name", "avatar_element_type")
    add("名前", "nickname", "nick_name", "game_name")
    add("プレイヤー", "user", "user_info", "role", "role_info", "player", "player_info")
    add("サーバー", "region", "server", "region_name", "server_name")
    add("ゲーム", "game", "game_id", "game_biz", "gids")
    add("キャラクター", "avatars", "avatar_list", "characters", "avatar", "avatar_info", "avatar_ids", "role_list", "role_combat")
    add("パーティ", "teams", "team", "lineup", "lineups", "roles", "avatar_lineup", "avatar_team")
    add("敵", "monsters", "enemy", "enemies", "monster", "boss", "boss_list")
    add("武器", "weapon", "weapons", "weapon_info", "equip", "equip_info", "equipment")
    add("光円錐", "light_cone", "light_cone_info")
    add("聖遺物", "reliquary_list", "reliquaries", "artifacts", "reliquary", "reliquary_consume")
    add("遺物", "relics", "relic", "ornaments", "relic_list")
    add("ディスク", "disc", "disc_list", "discs")
    add("音動機", "weapon_wiki", "w_engine")
    add("天賦・スキル", "skills", "skill", "skill_list", "talents", "skill_info", "skills_consume")
    add("ステータス", "stats", "stat", "properties", "property", "main_property", "base_stats")
    add("追加ステータス", "sub_property", "sub_properties", "sub_affix", "affix_list")
    add("セット効果", "set", "set_name", "set_effect", "suit")
    add("会心率", "crit_rate", "critical_hit_rate")
    add("会心ダメージ", "crit_dmg", "critical_hit_dmg")
    add("攻撃力", "attack", "atk")
    add("防御力", "defense", "def")
    add("最大HP", "max_hp", "hp")
    add("履歴", "records", "record", "history", "history_list", "battle_list")
    add("ステージ", "floor", "floors", "level_list", "stage", "stage_list", "levels", "maze_name", "node_list")
    add("戦闘", "battles", "battle", "schedule_data", "detail_data", "battle_info")
    add("クリア状況", "is_finished", "is_clear", "is_pass", "finish", "completed")
    add("階層", "max_floor", "floor_num", "floor_nums", "layer_id")
    add("星", "star", "star_num", "total_star", "total_stars", "max_star")
    add("スコア", "score", "total_score", "max_score", "rating_score")
    add("経過ラウンド", "round_num", "rounds", "round")
    add("クリア時間", "cost_time", "clear_time", "finish_time")
    add("深境螺旋", "spiral_abyss")
    add("深境螺旋（前期）", "previous_spiral_abyss")
    add("幻想シアター", "imaginarium_theater")
    add("追憶のエコー", "envisaged_echoes")
    add("幽境の激戦", "stygian_onslaught")
    add("忘却の庭", "forgotten_hall")
    add("模擬宇宙", "simulated_universe")
    add("虚構叙事", "pure_fiction")
    add("末日の幻影", "apocalyptic_shadow")
    add("異相演算", "anomaly_arbitration")
    add("式輿防衛", "shiyu_defense")
    add("危局強襲戦", "deadly_assault")
    add("殲滅シミュレーション", "annihilation_simulacrum")
    add("零号ホロウ", "lost_void")
    add("閾値シミュレーション", "threshold_simulation", "threshold_simulation_brief")
    add("量子多様体", "old_abyss")
    add("超弦空間", "superstring_abyss")
    add("記憶戦場", "memorial_arena")
    add("往世楽土", "elysian_realm")
    add("戦乙女", "battlesuits")
    add("七聖召喚の戦績", "tcg_preview")
    add("七聖召喚の対戦履歴", "tcg_matches")
    add("イベント", "activities", "activity_list", "act_list", "events", "event_list")
    add("開催期間", "schedule", "time_range", "duration")
    add("開始", "start_time", "begin_time", "start", "schedule_begin_time")
    add("終了", "end_time", "finish_at", "end", "schedule_end_time")
    add("日時", "time", "timestamp", "date", "created_at", "create_time", "updated_at")
    add("チェックイン", "is_sign")
    add("チェックイン日数", "total_sign_day", "sign_cnt")
    add("報酬", "rewards", "awards", "reward_list", "reward")
    add("所持数", "owned", "owned_num", "available_num")
    add("個数", "num", "count", "cnt", "amount", "item_num")
    add("不足数", "lack_num", "lacking")
    add("獲得元", "action", "action_name", "sources", "source", "action_id")
    add("種類", "type", "category", "item_type", "root_type", "gacha_type", "operator_type")
    add("状態", "status", "state", "is_public", "is_locked")
    add("進捗", "progress", "current", "cur_progress")
    add("最大値", "total_progress", "max", "maximum")
    add("ポイント", "point", "points")
    add("必要ポイント", "cost")
    add("在庫", "stock")
    add("交換コード", "exchange_code", "code")
    add("タスク", "task_list")
    add("交換品", "exchange_award_list")
    add("カード", "cards", "card_list", "role_cards", "actions", "action_cards")
    add("デッキ", "deck", "deck_list")
    add("タグ", "tags", "labels", "label_list")
    add("編成・シナリオ", "tree", "children")
    add("セクション", "modules", "components", "sections")
    add("アカウント", "game_roles_list", "game_role")
    add("所持素材", "available_material")
    add("洞天コピー", "blueprint")
    add("洞天コピー番号", "share_code")
    add("閲覧数", "view_cnt")
    add("いいね", "digg_cnt")
    add("保存数", "store_cnt")
    add("コピー数", "copy_cnt")
    add("日付", "day", "year", "month", "data_month")
    add("現在", "final")
    add("基礎値", "base")
    add("増加分", "add")
    add("数値", "value")
    add("解放済み", "is_unlock", "unlocked")
    add("ステータス", "selected_properties", "base_properties", "extra_properties", "element_properties")
    add("追加効果", "sub_property_list", "skill_affix_list")
    add("必要素材", "consume_list", "id_consume_list", "overall_consume", "consume", "materials")
    add("キャラクター育成素材", "avatar_consume")
    add("武器育成素材", "weapon_consume")
    add("天賦育成素材", "avatar_skill_consume")
    add("素材の内訳", "overall_material_consume", "single_role_result")
    add("詳細", "detail", "details", "data", "page", "info", "list", "items", "results")
    add("原石", "primogems", "primogem")
    add("モラ", "mora")
    add("星玉", "hcoin")
    add("ポリクローム", "polychromes", "polychrome")
    add("キャラクター数", "avatar_num", "avatar_count")
    add("アチーブメント数", "achievement_num", "achievement_count")
    add("プレイ日数", "active_day_number", "active_days")
    add("宝箱", "treasure", "chest")
}

internal fun localizedMonth(value: String): String {
    val match = Regex("([0-9]{4})[-/]?([0-9]{2})").matchEntire(value)
    if (match != null) return "${match.groupValues[1]}年${match.groupValues[2].toInt()}月"
    return value.toIntOrNull()?.takeIf { it in 1..12 }?.let { "${it}月" } ?: value
}
