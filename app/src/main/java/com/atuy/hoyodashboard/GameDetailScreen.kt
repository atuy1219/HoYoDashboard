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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.atuy.hoyodashboard.api.UpstreamActionDefinition
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
    val api = remember {
        UpstreamApiClient(onCookieUpdate = { SecureCookieStore.save(context, it) })
    }
    val account = remember(item.role) { ApiAccount.from(item.role) }
    val features = remember(item.role) {
        UpstreamFeatureClient(api, { SecureCookieStore.read(context).orEmpty() }, account)
    }
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

    LaunchedEffect(item.role, activeTab, refreshToken) {
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
                    gacha = features.gachaHistory(perBannerLimit = 100)
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
                DetailTab.ACTIONS -> ActionPanel(
                    { SecureCookieStore.read(context).orEmpty() }, account, actions
                )
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
    val visibleRecords = records.take(100)
    SectionCard("ガチャ履歴 " + records.size + "件取得") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ValueRow("★5", fiveStars.toString())
            ValueRow("★4", fourStars.toString())
            ValueRow("★5 UP", records.count { it.rarity >= 5 && it.isUp == true }.toString())
        }
        if (records.isEmpty()) Text("履歴がありません")
        if (records.size > visibleRecords.size) {
            Text("一覧は最新100件。天井集計は各祈願種別の取得データを使用します。")
        }
        records.groupBy { it.bannerType }.toSortedMap().forEach { (bannerType, pool) ->
            val newestFirst = pool.sortedByDescending { it.timestamp }
            val pity = newestFirst.takeWhile { it.rarity < 5 }.size
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("祈願種別 " + bannerType, fontWeight = FontWeight.SemiBold)
                ValueRow("現在の★5まで", pity.toString() + "回")
                ValueRow("★5 / ★4", pool.count { it.rarity >= 5 }.toString() + " / " + pool.count { it.rarity == 4 })
            }
        }
        HorizontalDivider(color = Color(0xFF394252))
        visibleRecords.forEach { record ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(record.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(if (record.rarity > 0) "★" + record.rarity else "—")
                }
                Text(
                    record.time + " · " + record.bannerType +
                        (if (record.rarity >= 5 && record.isUp == true) " · UP" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (record != visibleRecords.lastOrNull()) HorizontalDivider(color = Color(0xFF394252))
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
    cookieProvider: () -> String,
    account: ApiAccount,
    actions: UpstreamActions
) {
    val scope = rememberCoroutineScope()
    var busy by remember(account) { mutableStateOf(false) }
    var message by remember(account) { mutableStateOf<String?>(null) }
    var result by remember(account) { mutableStateOf<JSONObject?>(null) }
    var code by remember(account) { mutableStateOf("") }
    val advancedActions = remember(account) {
        actions.availableActions(account).filterNot {
            it.name == "claim_daily_reward" || it.name == "redeem_code"
        }
    }
    var actionMenuExpanded by remember(account) { mutableStateOf(false) }
    var selectedAction by remember(account) { mutableStateOf<UpstreamActionDefinition?>(null) }
    var actionParameters by remember(account) { mutableStateOf("") }
    var confirmAdvancedAction by remember(account) { mutableStateOf(false) }

    fun runAdvancedAction() {
        val definition = selectedAction ?: return
        val arguments = actionParameters.lines().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else {
                line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
        }.toMap()
        scope.launch {
            busy = true
            message = null
            result = null
            try {
                val response = actions.execute(cookieProvider(), account, definition.name, arguments)
                result = response
                message = if (response.optInt("retcode", 0) == 0) {
                    response.optString("message").ifBlank { "操作を完了しました" }
                } else response.optString("message").ifBlank { "操作に失敗しました" }
            } catch (exception: Exception) {
                message = exception.message ?: "操作に失敗しました"
            } finally {
                busy = false
            }
        }
    }

    SectionCard("HoYoLAB操作") {
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    message = null
                    result = null
                    try {
                        val response = actions.claimDailyReward(cookieProvider(), account)
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
            enabled = !busy && cookieProvider().isNotBlank(),
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
                            val response = actions.redeemCode(cookieProvider(), account, code)
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
                enabled = !busy && code.isNotBlank() && cookieProvider().isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("コードを交換") }
        }
    }
    SectionCard("高度な操作") {
        TextButton(onClick = { actionMenuExpanded = true }) {
            Text(selectedAction?.title ?: "操作を選択")
        }
        DropdownMenu(
            expanded = actionMenuExpanded,
            onDismissRequest = { actionMenuExpanded = false }
        ) {
            advancedActions.forEach { definition ->
                DropdownMenuItem(
                    text = { Text(definition.title) },
                    onClick = {
                        selectedAction = definition
                        actionParameters = ""
                        actionMenuExpanded = false
                    }
                )
            }
        }
        selectedAction?.let { definition ->
            Text(
                if (definition.required.isEmpty()) "追加パラメータは不要です"
                else "必須: " + definition.required.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = actionParameters,
                onValueChange = { actionParameters = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("key=value（1行につき1件）") },
                minLines = 2
            )
            Button(
                onClick = {
                    if (definition.confirmationRequired) confirmAdvancedAction = true
                    else runAdvancedAction()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("操作を実行") }
        }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    result?.let { JsonPanel("操作結果", it) }

    val pendingAction = selectedAction
    if (confirmAdvancedAction && pendingAction != null) {
        AlertDialog(
            onDismissRequest = { confirmAdvancedAction = false },
            title = { Text(pendingAction.title) },
            text = { Text("アカウントに変更を加える操作です。入力内容を確認して実行してください。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAdvancedAction = false
                    runAdvancedAction()
                }) { Text("実行") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAdvancedAction = false }) { Text("キャンセル") }
            }
        )
    }
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
