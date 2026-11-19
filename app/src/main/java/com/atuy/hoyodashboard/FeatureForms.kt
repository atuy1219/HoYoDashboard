package com.atuy.hoyodashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.atuy.hoyodashboard.api.japaneseApiError
import com.atuy.hoyodashboard.api.ApiGame
import com.atuy.hoyodashboard.api.ApiMethod
import com.atuy.hoyodashboard.api.ApiScope
import com.atuy.hoyodashboard.api.ApiRegion
import org.json.JSONArray
import com.atuy.hoyodashboard.api.UpstreamFeatureClient
import kotlinx.coroutines.CancellationException

internal data class FeatureField(
    val key: String,
    val label: String = argumentLabel(key),
    val required: Boolean = true,
    val choices: Map<String, String> = emptyMap(),
    val numeric: Boolean = false
)

private val lineupModes = linkedMapOf(
    "Chasm" to "忘却の庭", "Story" to "虚構叙事", "Boss" to "末日の幻影", "Peak" to "異相の仲裁"
)

internal fun featureFields(method: ApiMethod, game: ApiGame, region: ApiRegion): List<FeatureField> {
    val extra = when (method.name) {
        "get_starrail_lineup_schedules" -> listOf("type")
        "get_zzz_agent_upgrade_guide" -> listOf("avatar_id")
        "get_genshin_diary", "get_starrail_diary", "get_zzz_diary" -> listOf("month")
        "genshin_diary_log", "starrail_diary_log" -> listOf("month", "type")
        "get_hoyolab_user", "get_record_cards" -> listOf("uid")
        "chronicle_signal_history" -> listOf("gacha_type")
        "get_lineups" -> listOf("order")
        "get_tcg_decks" -> listOf("keywords")
        "get_accompany_character_posts" -> listOf("scene", "list_type")
        "get_banner_details" -> listOf("banner_ids")
        "get_genshin_spiral_abyss", "get_starrail_challenge", "get_starrail_pure_fiction",
        "get_starrail_apc_shadow", "get_shiyu_defense", "get_deadly_assault",
        "get_annihilation_simulacrum", "get_threshold_simulation", "get_stygian_onslaught" -> listOf("schedule_type")
        else -> emptyList()
    }
    val fields = (method.required + extra + if (method.authKeyRequired) listOf("authkey") else emptyList()).filterNot { key ->
        (method.scope == ApiScope.MIMO && key in setOf("game_id", "version_id")) || key == "avatar_list"
    }
    return fields.map { key ->
        when (key) {
            "type" -> when {
                method.name == "get_starrail_lineup_schedules" -> FeatureField(key, "モード", choices = lineupModes - "Peak")
                method.name.startsWith("get_starrail_lineup") -> FeatureField(key, "モード", choices = lineupModes)
                else -> FeatureField(key, "通貨", choices = when (game) {
                    ApiGame.GENSHIN -> mapOf("1" to "原石", "2" to "モラ")
                    ApiGame.STARRAIL -> mapOf("1" to "星玉", "2" to "星軌チケット")
                    else -> mapOf("PolychromesData" to "ポリクローム", "MatserTapeData" to "マスターテープ", "BooponsData" to "ボンプチケット")
                })
            }
            "operator_type" -> FeatureField(key, choices = mapOf("1" to "獲得", "2" to "消費"))
            "schedule_type" -> FeatureField(key, "期間", choices = mapOf("1" to "今期", "2" to "前期"))
            "order" -> FeatureField(key, "並び順", choices = mapOf("Hot" to "人気順", "CreatedTime" to "新着順"))
            "scene" -> FeatureField(key, "投稿の種類", choices = mapOf("1" to "作品", "2" to "ディスカッション"))
            "list_type" -> FeatureField(key, "並び順", choices = mapOf("hot" to "人気順", "new" to "新着順"))
            "authkey" -> FeatureField(key, required = region == ApiRegion.CHINESE)
            "gacha_type" -> FeatureField(key, required = key in method.required, choices = when (game) {
                ApiGame.STARRAIL -> listOf(1, 2, 11, 12, 21, 22)
                ApiGame.ZZZ -> listOf(1, 2, 3, 5, 102, 103)
                else -> listOf(100, 200, 301, 302, 400, 500)
            }.associate { it.toString() to bannerLabel(game, it) })
            "month" -> FeatureField(
                key, when (game) {
                    ApiGame.GENSHIN -> "対象月（1〜12）"
                    ApiGame.STARRAIL -> "対象月（年月6桁）"
                    else -> "対象月"
                }, required = key in method.required, numeric = game != ApiGame.ZZZ
            )
            "avatar_id" -> FeatureField(key, if (game == ApiGame.ZZZ) "エージェントID" else argumentLabel(key), numeric = true)
            "banner_ids" -> FeatureField(key, required = game == ApiGame.STARRAIL)
            "menu_id" -> FeatureField(key, choices = wikiCategories)
            else -> FeatureField(key, required = key in method.required,
                numeric = key.endsWith("_id") && key != "entry_page_ids" || key in setOf("uid", "id", "share_code", "floor"))
        }
    }
}

private val wikiCategories = linkedMapOf(
    "2" to "原神・キャラクター", "4" to "原神・武器", "5" to "原神・聖遺物", "7" to "原神・敵"
)

internal fun actionFields(keys: List<String>): List<FeatureField> = keys.map { key ->
    when (key) {
        "setting_id" -> FeatureField(key, choices = mapOf("1" to "戦績", "2" to "キャラクター詳細", "3" to "リアルタイムノート"))
        "public" -> FeatureField(key, choices = mapOf("true" to "公開", "false" to "非公開"))
        "game_id" -> FeatureField(key, choices = mapOf("2" to "原神", "5" to "HoYoLAB", "6" to "スターレイル", "8" to "ゼンレスゾーンゼロ"))
        "voice_language", "script_language" -> FeatureField(key, choices = mapOf(
            "ja-jp" to "日本語", "en-us" to "英語", "zh-cn" to "中国語（簡体字）",
            "zh-tw" to "中国語（繁体字）", "ko-kr" to "韓国語"
        ))
        else -> FeatureField(key, numeric = key.endsWith("_id"))
    }
}

internal fun fieldReady(field: FeatureField, value: String): Boolean {
    if (value.isBlank()) return !field.required
    return when {
        field.choices.isNotEmpty() -> value in field.choices
        field.numeric -> value.toLongOrNull()?.let { it > 0 } == true
        field.key == "entry_page_ids" || field.key == "character_ids" ->
            value.split(',').all { it.trim().toLongOrNull()?.let { id -> id > 0 } == true }
        else -> true
    }
}

internal fun prepareArguments(method: ApiMethod, args: Map<String, String>): Map<String, String> {
    val values = args.mapValues { it.value.trim() }.filterValues(String::isNotBlank).toMutableMap()
    if (method.name == "get_wiki_pages") {
        values["entry_page_ids"]?.let { values["entry_page_ids"] = JSONArray(it.split(',').map { id -> id.trim().toLong() }).toString() }
    }
    if (method.name == "get_banner_details") {
        values["banner_ids"]?.let { values["banner_ids"] = JSONArray(it.split(',').map(String::trim)).toString() }
    }
    if (method.name == "get_zzz_agent_upgrade_guide") {
        values.remove("avatar_id")?.let { id ->
            values["avatar_list"] = """[{"avatar_id":${id.toLong()},"is_teaser":false,"teaser_need_weapon":false,"teaser_sp_skill":false}]"""
        }
    }
    return values
}

@Composable
internal fun FeatureInput(field: FeatureField, value: String, onChange: (String) -> Unit) {
    if (field.choices.isNotEmpty()) {
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(field.choices[value] ?: field.label)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                field.choices.forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        onChange(key)
                        menuOpen = false
                    })
                }
            }
        }
    } else {
        val secret = field.key == "authkey"
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(field.label) },
            singleLine = field.key != "content",
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    field.numeric -> KeyboardType.Number
                    secret -> KeyboardType.Password
                    else -> KeyboardType.Text
                }
            ),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun StarRailLineupForm(
    features: UpstreamFeatureClient,
    onClose: () -> Unit,
    onSelect: (Map<String, String>) -> Unit
) {
    var mode by remember { mutableStateOf("Chasm") }
    var modeData by remember { mutableStateOf<JSONArray?>(null) }
    var schedules by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var schedule by remember { mutableStateOf("") }
    var floor by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(mode) {
        loading = true
        error = null
        schedule = ""
        floor = ""
        try {
            if (modeData == null) modeData = features.raw("get_starrail_lineup_game_modes").optJSONArray("tree")
            val data = features.raw("get_starrail_lineup_schedules", mapOf("type" to mode)).optJSONArray("schedule")
            val rows = if (data == null) emptyList() else (0 until data.length()).mapNotNull(data::optJSONObject)
            schedules = rows.mapNotNull { row ->
                val id = row.optString("group_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                id to row.optString("name_mi18n").ifBlank { row.optString("schedule_begin_time") }
            }
            schedule = rows.firstOrNull { it.optBoolean("is_now") }?.optString("group_id")
                ?: schedules.firstOrNull()?.first.orEmpty()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = japaneseApiError(exception)
        } finally {
            loading = false
        }
    }
    val tree = modeData
    val selectedMode = if (tree == null) null else (0 until tree.length()).mapNotNull(tree::optJSONObject)
        .firstOrNull { it.optString("root_type") == mode }
    val rows = selectedMode?.optJSONArray("children")?.optJSONObject(0)?.optJSONArray("children")
    val floors = if (rows == null) emptyMap() else (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val id = row.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val label = row.optString("name").ifBlank { row.optJSONObject("extend")?.optString("floor").orEmpty() }
        id to label
    }.toMap()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("スターレイルの編成") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FeatureInput(FeatureField("mode", "モード", choices = lineupModes - "Peak"), mode) { mode = it }
                when {
                    loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    error != null -> Text(displayText(error.orEmpty()), color = MaterialTheme.colorScheme.error)
                    schedules.isEmpty() || floors.isEmpty() -> Text("表示できる開催期間がありません")
                    else -> {
                        FeatureInput(FeatureField("schedule", "開催期間", choices = schedules.toMap()), schedule) { schedule = it }
                        FeatureInput(FeatureField("floor", "ステージ", choices = floors), floor) { floor = it }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSelect(mapOf("type" to mode, "tag_id" to floor, "group_id" to schedule)) },
                enabled = !loading && error == null && floor in floors && schedules.any { it.first == schedule }
            ) { Text("編成を見る") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("キャンセル") } }
    )
}
