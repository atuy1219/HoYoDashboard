package com.atuy.hoyodashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atuy.hoyodashboard.api.ApiAccount
import com.atuy.hoyodashboard.api.ApiGame
import com.atuy.hoyodashboard.api.CharacterPreview
import com.atuy.hoyodashboard.api.DailyNote
import com.atuy.hoyodashboard.api.DashboardItem
import com.atuy.hoyodashboard.api.DiarySummary
import com.atuy.hoyodashboard.api.Game
import com.atuy.hoyodashboard.api.GameProfile
import com.atuy.hoyodashboard.api.GachaRecord
import com.atuy.hoyodashboard.api.GenshinProfile
import com.atuy.hoyodashboard.api.HonkaiProfile
import com.atuy.hoyodashboard.api.StarRailProfile
import com.atuy.hoyodashboard.api.TransactionItem
import com.atuy.hoyodashboard.api.UpstreamActions
import com.atuy.hoyodashboard.api.UpstreamApiClient
import com.atuy.hoyodashboard.api.UpstreamFeatureClient
import com.atuy.hoyodashboard.api.ZzzProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class DetailTab(val label: String) {
    OVERVIEW("概要"),
    CHARACTERS("キャラクター"),
    BATTLE("戦績"),
    GACHA("ガチャ"),
    DIARY("月次記録"),
    ACTIONS("操作"),
    API("API")
}

@Composable
internal fun GameDetailScreen(
    item: DashboardItem,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cookie = remember(item.role) { SecureCookieStore.read(context).orEmpty() }
    val api = remember {
        UpstreamApiClient(onCookieUpdate = { SecureCookieStore.save(context, it) })
    }
    val account = remember(item.role) { ApiAccount.from(item.role) }
    val features = remember(item.role, cookie) { UpstreamFeatureClient(api, cookie, account) }
    val actions = remember { UpstreamActions() }
    val tabs = remember(item.role.game) { tabsFor(item.role.game) }

    var selectedIndex by remember(item.role) { mutableIntStateOf(0) }
    var refreshToken by remember(item.role) { mutableIntStateOf(0) }
    var profile by remember(item.role) { mutableStateOf<GameProfile?>(null) }
    var characters by remember(item.role) { mutableStateOf<List<CharacterPreview>?>(null) }
    var characterDetails by remember(item.role) { mutableStateOf<List<JSONObject>?>(null) }
    var battle by remember(item.role) { mutableStateOf<JSONObject?>(null) }
    var gacha by remember(item.role) { mutableStateOf<List<GachaRecord>?>(null) }
    var diary by remember(item.role) { mutableStateOf<DiarySummary?>(null) }
    var transactions by remember(item.role) { mutableStateOf<List<TransactionItem>?>(null) }
    var activeLoading by remember(item.role) { mutableStateOf(false) }
    var activeError by remember(item.role) { mutableStateOf<String?>(null) }

    val activeTab = tabs.getOrElse(selectedIndex) { tabs.first() }

    LaunchedEffect(item.role, activeTab, refreshToken, cookie) {
        activeLoading = activeTab in setOf(
            DetailTab.OVERVIEW, DetailTab.CHARACTERS, DetailTab.BATTLE,
            DetailTab.GACHA, DetailTab.DIARY
        )
        activeError = null
        if (!activeLoading) return@LaunchedEffect
        try {
            when (activeTab) {
                DetailTab.OVERVIEW -> {
                    if (item.role.game != Game.TOT && profile == null) {
                        profile = features.profile()
                    }
                }
                DetailTab.CHARACTERS -> {
                    if (characters == null) characters = features.characters()
                    if (characterDetails == null) characterDetails = features.detailedCharacters()
                }
                DetailTab.BATTLE -> if (battle == null) {
                    battle = features.fullBattleRecord()
                }
                DetailTab.GACHA -> if (gacha == null) {
                    gacha = features.gachaHistory(limit = 100)
                }
                DetailTab.DIARY -> if (diary == null) {
                    diary = features.monthlyDiary()
                    if (item.role.game == Game.GENSHIN && transactions == null) {
                        transactions = features.transactionHistory(limit = 100)
                    }
                }
                DetailTab.ACTIONS, DetailTab.API -> Unit
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            activeError = exception.message ?: "取得に失敗しました"
        } finally {
            activeLoading = false
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("戻る") }
            Column(Modifier.weight(1f)) {
                Text(item.role.game.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    item.role.nickname + " · " + item.role.uid,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                profile = null
                characters = null
                characterDetails = null
                battle = null
                gacha = null
                diary = null
                transactions = null
                refreshToken++
                onRefresh()
            }) { Text("更新") }
        }
        ScrollableTabRow(selectedTabIndex = selectedIndex) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedIndex == index,
                    onClick = { selectedIndex = index },
                    text = { Text(tab.label) }
                )
            }
        }
        if (activeLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        activeError?.let {
            Text(
                it,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (activeTab) {
                DetailTab.OVERVIEW -> {
                    NoteOverview(item.note, item.error, item.role.game.resource)
                    profile?.let { ProfileSummary(it) }
                    if (item.role.game == Game.TOT) {
                        InfoCard("未定事件簿", "このゲームにはBattle ChronicleのプロフィールAPIがありません。")
                    }
                }
                DetailTab.CHARACTERS -> {
                    val rows = characters
                    if (rows != null) CharacterList(rows, characterDetails.orEmpty())
                }
                DetailTab.BATTLE -> battle?.let { JsonPanel("戦闘記録", it) }
                DetailTab.GACHA -> gacha?.let { GachaList(it) }
                DetailTab.DIARY -> diary?.let { DiaryPanel(it, transactions) }
                DetailTab.ACTIONS -> ActionPanel(cookie, account, actions)
                DetailTab.API -> UpstreamApiExplorer(item.role)
            }
        }
    }
}

private fun tabsFor(game: Game): List<DetailTab> = when (game) {
    Game.GENSHIN -> listOf(
        DetailTab.OVERVIEW, DetailTab.CHARACTERS, DetailTab.BATTLE,
        DetailTab.GACHA, DetailTab.DIARY, DetailTab.ACTIONS, DetailTab.API
    )
    Game.STAR_RAIL, Game.ZZZ -> listOf(
        DetailTab.OVERVIEW, DetailTab.CHARACTERS, DetailTab.BATTLE,
        DetailTab.GACHA, DetailTab.DIARY, DetailTab.ACTIONS, DetailTab.API
    )
    Game.HONKAI -> listOf(
        DetailTab.OVERVIEW, DetailTab.CHARACTERS, DetailTab.BATTLE,
        DetailTab.ACTIONS, DetailTab.API
    )
    Game.TOT -> listOf(DetailTab.OVERVIEW, DetailTab.ACTIONS, DetailTab.API)
}

@Composable
private fun NoteOverview(note: DailyNote?, error: String?, resourceLabel: String) {
    if (note == null) {
        InfoCard("リアルタイム情報", error ?: "取得できませんでした")
        return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("リアルタイム情報", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(resourceLabel, modifier = Modifier.weight(1f))
                Text(
                    note.resource.current.toString() + " / " + note.resource.maximum,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            LinearProgressIndicator(
                progress = { note.resource.fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            note.secondsUntilFull?.let { seconds ->
                ValueRow("全回復まで", (seconds / 3600).toString() + "時間 " + ((seconds % 3600) / 60) + "分")
            }
            note.daily?.let { ValueRow("デイリー", it.current.toString() + " / " + it.maximum) }
            note.dailyCompleted?.let { ValueRow("デイリー報酬", if (it) "受取済み" else "未受取") }
            note.details.forEach { ValueRow(it.name, it.value) }
        }
    }
}

@Composable
private fun ProfileSummary(profile: GameProfile) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("プレイヤー情報", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ValueRow("名前", profile.nickname)
            ValueRow("レベル", profile.level.toString())
            when (profile) {
                is GenshinProfile -> {
                    ValueRow("アクティブ日数", profile.activeDays.toString())
                    ValueRow("アチーブメント", profile.achievements.toString())
                    ValueRow("キャラクター", profile.avatarCount.toString())
                    profile.spiralAbyss?.let { ValueRow("深境螺旋", it) }
                }
                is StarRailProfile -> {
                    ValueRow("アクティブ日数", profile.activeDays.toString())
                    ValueRow("アチーブメント", profile.achievements.toString())
                    ValueRow("キャラクター", profile.avatarCount.toString())
                    profile.forgottenHall?.let { ValueRow("忘却の庭", it) }
                }
                is ZzzProfile -> {
                    ValueRow("アチーブメント", profile.achievementCount.toString())
                    ValueRow("エージェント", profile.agentCount.toString())
                    ValueRow("ボンプ", profile.bangbooCount.toString())
                }
                is HonkaiProfile -> ValueRow("戦乙女", profile.battlesuitCount.toString())
            }
        }
    }
}

@Composable
private fun CharacterList(characters: List<CharacterPreview>, details: List<JSONObject>) {
    var showDetails by remember(characters, details) { mutableStateOf(false) }
    SectionCard("キャラクター " + characters.size + "人") {
        if (details.isNotEmpty()) {
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(if (showDetails) "育成・装備データを閉じる" else "育成・装備データを表示")
            }
            if (showDetails) {
                val data = JSONObject().put("list", JSONArray().apply { details.forEach { put(it) } })
                JsonPanel("キャラクター詳細", data)
            }
        }
        if (characters.isEmpty()) {
            Text("キャラクター情報がありません")
        } else {
            characters.forEach { character ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            character.name.ifBlank { "ID " + character.id },
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold
                        )
                        if (character.rarity > 0) Text("★" + character.rarity)
                    }
                    val details = listOfNotNull(
                        character.element?.takeIf { it.isNotBlank() },
                        character.weapon?.takeIf { it.isNotBlank() },
                        if (character.level > 0) "Lv." + character.level else null
                    )
                    if (details.isNotEmpty()) {
                        Text(details.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (character != characters.last()) HorizontalDivider(color = Color(0xFF394252))
            }
        }
    }
}

@Composable
private fun GachaList(records: List<GachaRecord>) {
    val fiveStars = records.count { it.rarity >= 5 }
    val fourStars = records.count { it.rarity == 4 }
    SectionCard("ガチャ履歴 " + records.size + "件") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ValueRow("★5", fiveStars.toString())
            ValueRow("★4", fourStars.toString())
        }
        if (records.isEmpty()) Text("履歴がありません")
        records.forEach { record ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(record.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(if (record.rarity > 0) "★" + record.rarity else "—")
                }
                Text(
                    record.time + " · " + record.bannerType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (record != records.last()) HorizontalDivider(color = Color(0xFF394252))
        }
    }
}

@Composable
private fun DiaryPanel(summary: DiarySummary, transactions: List<TransactionItem>?) {
    SectionCard("月次記録 " + (summary.month ?: "")) {
        summary.currentPrimogems?.let { ValueRow("原石", it.toString()) }
        summary.currentMora?.let { ValueRow("モラ", it.toString()) }
        summary.currentHcoin?.let { ValueRow("星玉", it.toString()) }
        summary.currentRailsPass?.let { ValueRow("星軌チケット", it.toString()) }
        summary.currentPolychromes?.let { ValueRow("ポリクローム", it.toString()) }
        summary.currentMasterTapes?.let { ValueRow("マスターテープ", it.toString()) }
        summary.currentBoopons?.let { ValueRow("ボンプチケット", it.toString()) }
        summary.categories.forEach { category ->
            ValueRow(category.name.ifBlank { category.id.orEmpty() }, category.amount.toString())
        }
        if (summary.currentPrimogems == null && summary.currentMora == null &&
            summary.currentHcoin == null && summary.currentPolychromes == null &&
            summary.categories.isEmpty()
        ) {
            Text("集計値を解析できませんでした。取得したレスポンスは保持されています。")
        }
    }
    if (transactions != null) {
        SectionCard("原石・通貨の取引履歴 " + transactions.size + "件") {
            transactions.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.action, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(item.amount.toString())
                    }
                    Text(item.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item != transactions.last()) HorizontalDivider(color = Color(0xFF394252))
            }
        }
    }
    JsonPanel("元データ", summary.raw)
}

@Composable
private fun ActionPanel(
    cookie: String,
    account: ApiAccount,
    actions: UpstreamActions
) {
    val scope = rememberCoroutineScope()
    var busy by remember(account) { mutableStateOf(false) }
    var message by remember(account) { mutableStateOf<String?>(null) }
    var result by remember(account) { mutableStateOf<JSONObject?>(null) }
    var code by remember(account) { mutableStateOf("") }

    SectionCard("HoYoLAB操作") {
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    message = null
                    result = null
                    try {
                        val response = actions.claimDailyReward(cookie, account)
                        result = response
                        message = if (response.optInt("retcode", 0) == 0) {
                            response.optString("message").ifBlank { "チェックイン処理を完了しました" }
                        } else response.optString("message").ifBlank { "チェックインに失敗しました" }
                    } catch (exception: Exception) {
                        message = exception.message ?: "チェックインに失敗しました"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && cookie.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "処理中…" else "デイリーチェックイン") }

        if (account.region == com.atuy.hoyodashboard.api.ApiRegion.OVERSEAS &&
            account.game != ApiGame.HONKAI
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("交換コード") },
                singleLine = true
            )
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        message = null
                        result = null
                        try {
                            val response = actions.redeemCode(cookie, account, code)
                            result = response
                            message = if (response.optInt("retcode", 0) == 0) {
                                response.optString("message").ifBlank { "コード交換処理を完了しました" }
                            } else response.optString("message").ifBlank { "コード交換に失敗しました" }
                        } catch (exception: Exception) {
                            message = exception.message ?: "コード交換に失敗しました"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && code.isNotBlank() && cookie.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("コードを交換") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    result?.let { JsonPanel("操作結果", it) }
}

@Composable
private fun JsonPanel(title: String, json: JSONObject) {
    SectionCard(title) {
        Text(
            json.toString(2),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoCard(title: String, message: String) {
    SectionCard(title) { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
