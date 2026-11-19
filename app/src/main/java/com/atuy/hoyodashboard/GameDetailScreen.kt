package com.atuy.hoyodashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.atuy.hoyodashboard.api.japaneseApiError
import com.atuy.hoyodashboard.api.ApiAccount
import com.atuy.hoyodashboard.api.ApiGame
import com.atuy.hoyodashboard.api.ApiRegion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.atuy.hoyodashboard.api.CharacterPreview
import com.atuy.hoyodashboard.api.CheckInStatus
import com.atuy.hoyodashboard.api.RewardItem
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
    ACTIONS("チェックイン・操作"),
    CALCULATOR("育成計算"),
    FEATURES("その他")
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
    var characterDetails by remember(item.role) { mutableStateOf<Map<Int, JSONObject>>(emptyMap()) }
    var gacha by remember(item.role) { mutableStateOf<List<GachaRecord>?>(null) }
    var gachaLimit by remember(item.role) { mutableIntStateOf(20) }
    var gachaKeyInput by remember(item.role) { mutableStateOf("") }
    var gachaKey by remember(item.role) { mutableStateOf("") }
    var miliastra by remember(item.role) { mutableStateOf(false) }
    val gachaNeedsKey = account.region == ApiRegion.CHINESE && account.game != ApiGame.ZZZ
    var diary by remember(item.role) { mutableStateOf<DiarySummary?>(null) }
    var diaryMonth by remember(item.role) { mutableStateOf<String?>(null) }
    var diaryEntries by remember(item.role) { mutableStateOf<List<JSONObject>?>(null) }
    var diaryLimit by remember(item.role) { mutableIntStateOf(0) }
    var diaryCurrency by remember(item.role) { mutableIntStateOf(1) }
    var showTransactions by remember(item.role) { mutableStateOf(false) }
    var transactions by remember(item.role) { mutableStateOf<List<TransactionItem>?>(null) }
    var activeLoading by remember(item.role) { mutableStateOf(false) }
    var activeError by remember(item.role) { mutableStateOf<String?>(null) }

    val activeTab = tabs.getOrElse(selectedIndex) { tabs.first() }

    LaunchedEffect(item.role, activeTab, refreshToken, diaryMonth, diaryCurrency, showTransactions, gachaKey, miliastra, gachaLimit, diaryLimit) {
        activeLoading = activeTab in setOf(
            DetailTab.OVERVIEW, DetailTab.CHARACTERS, DetailTab.GACHA, DetailTab.DIARY
        )
        activeError = null
        if (!activeLoading) return@LaunchedEffect
        try {
            when (activeTab) {
                DetailTab.OVERVIEW -> {
                    if (item.role.game != Game.TOT && profile == null) {
                        profile = features.profileSummary()
                    }
                }
                DetailTab.CHARACTERS -> {
                    if (characters == null) characters = features.characters()
                }
                DetailTab.GACHA -> if (gacha == null && (!gachaNeedsKey || gachaKey.isNotBlank())) {
                    gacha = features.gachaHistory(perBannerLimit = gachaLimit, authKey = gachaKey, miliastra = miliastra)
                }
                DetailTab.DIARY -> {
                    if (showTransactions) {
                        if (transactions == null) transactions = features.transactionHistory(limit = 100)
                    } else {
                        if (diary == null) diary = features.monthlyDiary(diaryMonth)
                        val month = diaryMonth ?: diary?.month
                        if (month != null && diaryLimit > 0 && diaryEntries == null) {
                            diaryEntries = features.monthlyDiaryEntries(month, diaryCurrency, diaryLimit)
                        }
                    }
                }
                DetailTab.BATTLE, DetailTab.ACTIONS, DetailTab.CALCULATOR, DetailTab.FEATURES -> Unit
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            activeError = japaneseApiError(exception)
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
                when (activeTab) {
                    DetailTab.OVERVIEW -> {
                        profile = null
                        onRefresh()
                    }
                    DetailTab.CHARACTERS -> {
                        characters = null
                        characterDetails = emptyMap()
                    }
                    DetailTab.GACHA -> gacha = null
                    DetailTab.DIARY -> if (showTransactions) transactions = null else {
                        diary = null
                        diaryEntries = null
                    }
                    else -> Unit
                }
                refreshToken++
            }, enabled = !activeLoading) { Text("更新") }
        }
        PrimaryScrollableTabRow(selectedTabIndex = selectedIndex) {
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
                    profile?.let { ProfileSummary(it, item.role.nickname, item.role.level) }
                    if (item.role.game == Game.TOT) {
                        InfoCard("未定事件簿", "このゲームには戦績のプロフィール機能がありません。")
                    }
                }
                DetailTab.CHARACTERS -> {
                    val rows = characters
                    if (rows != null) CharacterList(rows, features, characterDetails) { id, detail ->
                        characterDetails = characterDetails + (id to detail)
                    }
                }
                DetailTab.BATTLE -> key(refreshToken) { FeatureDirectory(features, "戦闘記録") }
                DetailTab.GACHA -> {
                    if (account.game == ApiGame.GENSHIN) {
                        val pools = mapOf("normal" to "祈願", "miliastra" to "千星の奇域・衣装祈願")
                        FeatureInput(FeatureField("mode", "履歴の種類", choices = pools), if (miliastra) "miliastra" else "normal") {
                            if (miliastra != (it == "miliastra")) {
                                gacha = null
                                gachaLimit = if (it == "miliastra") 5 else 20
                                miliastra = it == "miliastra"
                            }
                        }
                    }
                    if (gachaNeedsKey) {
                        FeatureInput(FeatureField("authkey"), gachaKeyInput) { gachaKeyInput = it.trim() }
                        Button(enabled = !activeLoading && gachaKeyInput.isNotBlank(), onClick = {
                            gacha = null
                            gachaKey = gachaKeyInput
                            refreshToken++
                        }) { Text("履歴を表示") }
                    }
                    gacha?.let { GachaList(it, account.game) }
                    if (!activeLoading && gacha?.isNotEmpty() == true && gachaLimit < 100) {
                        TextButton(onClick = {
                            gacha = null
                            gachaLimit = 100
                        }) { Text("各種類の履歴を100件まで取得") }
                    }
                }
                DetailTab.DIARY -> {
                    if (account.game == ApiGame.GENSHIN && account.region == ApiRegion.OVERSEAS) {
                        TextButton(onClick = { showTransactions = !showTransactions }) {
                            Text(if (showTransactions) "月次記録を見る" else "ゲーム内取引履歴を見る")
                        }
                    }
                    if (showTransactions) {
                        transactions?.let { TransactionPanel(it) }
                    } else {
                        DiaryControls(account.game, diary, diaryMonth, diaryCurrency, onMonth = {
                            diaryMonth = it
                            diary = null
                            diaryEntries = null
                            diaryLimit = 0
                        }, onCurrency = {
                            diaryCurrency = it
                            diaryEntries = null
                            diaryLimit = 0
                        })
                        diary?.let { DiaryPanel(it, diaryEntries) }
                        if (diary != null && !activeLoading && (diaryLimit == 0 || (diaryEntries?.size ?: 0) >= diaryLimit)) {
                            TextButton(onClick = {
                                diaryEntries = null
                                diaryLimit = if (diaryLimit == 0) 100 else diaryLimit + 100
                            }) { Text(if (diaryLimit == 0) "獲得明細を見る" else "さらに明細を取得") }
                        }
                    }
                }
                DetailTab.ACTIONS -> key(refreshToken) { ActionPanel(
                    { SecureCookieStore.read(context).orEmpty() }, features, actions
                ) }
                DetailTab.CALCULATOR -> key(refreshToken) { CalculatorScreen(features) }
                DetailTab.FEATURES -> key(refreshToken) { FeatureDirectory(features) }
            }
        }
    }
}

private fun tabsFor(game: Game): List<DetailTab> = when (game) {
    Game.GENSHIN -> listOf(
        DetailTab.OVERVIEW, DetailTab.CHARACTERS, DetailTab.BATTLE,
        DetailTab.GACHA, DetailTab.DIARY, DetailTab.CALCULATOR, DetailTab.ACTIONS, DetailTab.FEATURES
    )
    Game.STAR_RAIL, Game.ZZZ -> listOf(
        DetailTab.OVERVIEW, DetailTab.CHARACTERS, DetailTab.BATTLE,
        DetailTab.GACHA, DetailTab.DIARY, DetailTab.ACTIONS, DetailTab.FEATURES
    )
    Game.HONKAI -> listOf(
        DetailTab.OVERVIEW, DetailTab.CHARACTERS, DetailTab.BATTLE,
        DetailTab.ACTIONS, DetailTab.FEATURES
    )
    Game.TOT -> listOf(DetailTab.OVERVIEW, DetailTab.ACTIONS, DetailTab.FEATURES)
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
private fun ProfileSummary(profile: GameProfile, nickname: String, level: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("プレイヤー情報", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ValueRow("名前", profile.nickname.ifBlank { nickname })
            ValueRow("レベル", (profile.level.takeIf { it > 0 } ?: level).toString())
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
private fun CharacterList(
    characters: List<CharacterPreview>,
    features: UpstreamFeatureClient,
    details: Map<Int, JSONObject>,
    onDetail: (Int, JSONObject) -> Unit
) {
    val game = features.account.game
    var selected by remember(characters) { mutableStateOf<CharacterPreview?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selected?.id, characters) {
        detailError = null
        val character = selected ?: return@LaunchedEffect
        if (game !in setOf(ApiGame.GENSHIN, ApiGame.ZZZ) || character.id in details) return@LaunchedEffect
        detailLoading = true
        try {
            val result = features.detailedCharacters(listOf(character.id)).firstOrNull()
            if (result != null) onDetail(character.id, result)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            detailError = japaneseApiError(exception)
        } finally {
            detailLoading = false
        }
    }
    var visible by remember(characters) { mutableIntStateOf(24) }
    SectionCard("キャラクター ${characters.size} 人") {
        if (characters.isEmpty()) Text("キャラクター情報がありません")
        characters.take(visible).forEachIndexed { index, character ->
            if (index > 0) HorizontalDivider(color = Color(0xFF394252))
            Row(
                Modifier.fillMaxWidth().clickable { selected = character }.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FeatureImage(character.icon)
                Column(Modifier.weight(1f)) {
                    Text(character.name.ifBlank { "キャラクター ${character.id}" }, fontWeight = FontWeight.SemiBold)
                    if (character.level > 0) Text("Lv.${character.level}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (character.rarity > 0) Text(characterRarity(game, character.rarity))
            }
        }
        if (visible < characters.size) TextButton(onClick = { visible += 24 }) { Text("さらに表示") }
    }
    selected?.let { character ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(character.name.ifBlank { "キャラクター詳細" }) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    if (detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    FeatureResult(details[character.id] ?: character.raw, "character")
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("閉じる") } }
        )
    }
}

@Composable
private fun GachaList(records: List<GachaRecord>, game: ApiGame) {
    val topRarity = if (game == ApiGame.ZZZ) 4 else 5
    val nextRarity = topRarity - 1
    val fiveStars = records.count { it.rarity >= topRarity }
    val fourStars = records.count { it.rarity == nextRarity }
    var limit by remember(records) { mutableIntStateOf(40) }
    val visibleRecords = records.take(limit)
    SectionCard("ガチャ履歴 " + records.size + "件取得") {
        val topRank = if (game == ApiGame.ZZZ) "S級" else "★5"
        val nextRank = if (game == ApiGame.ZZZ) "A級" else "★4"
        ValueRow(topRank, fiveStars.toString())
        ValueRow(nextRank, fourStars.toString())
        val featured = records.count { it.rarity >= topRarity && it.isUp == true }
        if (featured > 0) ValueRow("$topRank ピックアップ", featured.toString())
        if (records.isEmpty()) Text("履歴がありません")
        records.groupBy {
            if (game == ApiGame.GENSHIN && it.bannerType == 400) 301 else it.bannerType
        }.toSortedMap().forEach { (bannerType, pool) ->
            val newestFirst = pool.sortedByDescending { it.timestamp }
            val pity = newestFirst.takeWhile { it.rarity < topRarity }.size
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(bannerLabel(game, bannerType), fontWeight = FontWeight.SemiBold)
                ValueRow("前回の${topRank}から", (if (pity == pool.size) "確認できた範囲で " else "") + "${pity}回")
                ValueRow("$topRank / $nextRank", pool.count { it.rarity >= topRarity }.toString() + " / " + pool.count { it.rarity == nextRarity })
            }
        }
        HorizontalDivider(color = Color(0xFF394252))
        visibleRecords.forEach { record ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(record.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(when {
                        game == ApiGame.ZZZ -> mapOf(4 to "S級", 3 to "A級", 2 to "B級")[record.rarity] ?: "—"
                        record.rarity > 0 -> "★${record.rarity}"
                        else -> "—"
                    })
                }
                val time = if (record.timestamp > 0) Instant.ofEpochMilli(record.timestamp)
                    .atZone(ZoneId.of("Asia/Tokyo")).format(gachaDateFormat) else record.time
                Text(
                    time + " · " + bannerLabel(game, record.bannerType) +
                        (if (record.rarity >= topRarity && record.isUp == true) " · ピックアップ" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (record != visibleRecords.lastOrNull()) HorizontalDivider(color = Color(0xFF394252))
        }
        if (visibleRecords.size < records.size) TextButton(onClick = { limit += 40 }) { Text("さらに表示") }
    }
}

@Composable
private fun DiaryControls(
    game: ApiGame,
    summary: DiarySummary?,
    month: String?,
    currency: Int,
    onMonth: (String?) -> Unit,
    onCurrency: (Int) -> Unit
) {
    val months = remember(game, summary) {
        if (game == ApiGame.ZZZ) {
            val options = summary?.raw?.optJSONArray("optional_month")
            return@remember if (options == null) emptyMap() else (0 until options.length())
                .map { options.optString(it) }.filter(String::isNotBlank)
                .associateWith(::localizedMonth)
        }
        val now = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Tokyo"))
        (0L..2L).associate { offset ->
            val date = now.minusMonths(offset)
            val key = when (game) {
                ApiGame.STARRAIL -> date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
                else -> date.monthValue.toString()
            }
            key to "${date.year}年${date.monthValue}月"
        }
    }
    if (months.isNotEmpty()) {
        FeatureInput(FeatureField("month", "対象月", choices = months), month ?: summary?.month ?: months.keys.first()) { onMonth(it) }
    }
    val currencies = when (game) {
        ApiGame.GENSHIN -> mapOf("1" to "原石", "2" to "モラ")
        ApiGame.STARRAIL -> mapOf("1" to "星玉", "2" to "星軌チケット")
        else -> mapOf("1" to "ポリクローム", "2" to "マスターテープ", "3" to "ボンプチケット")
    }
    FeatureInput(FeatureField("type", "通貨", choices = currencies), currency.toString()) { onCurrency(it.toInt()) }
}

@Composable
private fun DiaryPanel(summary: DiarySummary, entries: List<JSONObject>?) {
    SectionCard(summary.month?.let(::localizedMonth) ?: "月次記録") {
        summary.currentPrimogems?.let { ValueRow("原石", it.toString()) }
        summary.currentMora?.let { ValueRow("モラ", it.toString()) }
        (summary.currentHcoin ?: summary.currentStellarJades)?.let { ValueRow("星玉", it.toString()) }
        summary.currentCredits?.let { ValueRow("信用ポイント", it.toString()) }
        summary.currentRailsPass?.let { ValueRow("星軌チケット", it.toString()) }
        summary.currentPolychromes?.let { ValueRow("ポリクローム", it.toString()) }
        summary.currentMasterTapes?.let { ValueRow("マスターテープ", it.toString()) }
        summary.currentBoopons?.let { ValueRow("ボンプチケット", it.toString()) }
        summary.categories.forEach { category ->
            Row(Modifier.fillMaxWidth()) {
                JapaneseContent(category.name.ifBlank { "その他" }, modifier = Modifier.weight(1f))
                Text(category.amount.toString())
            }
        }
        if (summary.currentPrimogems == null && summary.currentMora == null &&
            summary.currentHcoin == null && summary.currentStellarJades == null && summary.currentPolychromes == null &&
            summary.categories.isEmpty()
        ) {
            Text("集計を表示できませんでした")
        }
    }
    if (entries != null) SectionCard("獲得明細") {
        if (entries.isEmpty()) Text("明細はありません")
        else FeatureResult(JSONObject().put("list", JSONArray(entries)), "diary")
    }
}

@Composable
private fun TransactionPanel(transactions: List<TransactionItem>) {
    SectionCard("ゲーム内取引履歴") {
        if (transactions.isEmpty()) Text("取引はありません")
        transactions.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    JapaneseContent(item.action, modifier = Modifier.weight(1f))
                    Text(item.amount.toString())
                }
                Text(item.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item != transactions.last()) HorizontalDivider(color = Color(0xFF394252))
        }
    }
}

@Composable
private fun ActionPanel(
    cookieProvider: () -> String,
    features: UpstreamFeatureClient,
    actions: UpstreamActions
) {
    val account = features.account
    val scope = rememberCoroutineScope()
    var busy by remember(account) { mutableStateOf(false) }
    var message by remember(account) { mutableStateOf<String?>(null) }
    var result by remember(account) { mutableStateOf<JSONObject?>(null) }
    var code by remember(account) { mutableStateOf("") }
    var rewardStatus by remember(account) { mutableStateOf<CheckInStatus?>(null) }
    var claimedItem by remember(account) { mutableStateOf<RewardItem?>(null) }
    var rewardsVersion by remember(account) { mutableIntStateOf(0) }
    LaunchedEffect(account, rewardsVersion) {
        rewardStatus = try {
            features.checkInStatus()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
    }
    val advancedActions = remember(account) {
        actions.availableActions(account).filterNot {
            it.name == "claim_daily_reward" || it.name == "redeem_code"
        }
    }
    var actionMenuExpanded by remember(account) { mutableStateOf(false) }
    var selectedAction by remember(account) { mutableStateOf<UpstreamActionDefinition?>(null) }
    var actionParameters by remember(account) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var confirmAdvancedAction by remember(account) { mutableStateOf(false) }

    fun runAdvancedAction() {
        val definition = selectedAction ?: return
        val arguments = actionParameters.mapValues { (_, value) -> value.trim() }
        scope.launch {
            busy = true
            message = null
            result = null
            try {
                val response = actions.execute(cookieProvider(), account, definition.name, arguments)
                result = response
                message = "操作を完了しました"
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                message = japaneseApiError(exception)
            } finally {
                busy = false
            }
        }
    }

    SectionCard("デイリーチェックイン") {
        rewardStatus?.let {
            ValueRow("本日", if (it.signedIn) "チェックイン済み" else "未チェックイン")
            ValueRow("今月のチェックイン", "${it.claimedDays} 日")
        }
        claimedItem?.let { reward ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FeatureImage(reward.icon)
                Text("${reward.name} ×${reward.amount}")
            }
        }
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    message = null
                    result = null
                    claimedItem = null
                    try {
                        claimedItem = actions.claimDailyRewardItem(cookieProvider(), account)
                        message = "チェックインしました"
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        message = japaneseApiError(exception)
                    } finally {
                        rewardsVersion++
                        busy = false
                    }
                }
            },
            enabled = !busy && cookieProvider().isNotBlank() && rewardStatus?.signedIn != true,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "処理中…" else "デイリーチェックイン") }

    }
    if (account.region == ApiRegion.OVERSEAS &&
        account.game != ApiGame.HONKAI
    ) SectionCard("コード交換") {
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
                            message = "コードを交換しました"
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            message = japaneseApiError(exception)
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && code.isNotBlank() && cookieProvider().isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("コードを交換") }
    }
    SectionCard("アカウント操作") {
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
                        actionParameters = emptyMap()
                        message = null
                        result = null
                        actionMenuExpanded = false
                    }
                )
            }
        }
        selectedAction?.let { definition ->
            val fields = actionFields(definition.required)
            fields.forEach { field ->
                key(field.key) {
                    val displayField = if (definition.name == "set_calculator_sync" && field.key == "public") {
                        field.copy(label = "所持データ連携", choices = mapOf("true" to "有効", "false" to "無効"))
                    } else field
                    FeatureInput(displayField, actionParameters[field.key].orEmpty()) {
                        actionParameters = actionParameters + (field.key to it)
                    }
                }
            }
            Button(
                onClick = {
                    if (definition.confirmationRequired) confirmAdvancedAction = true
                    else runAdvancedAction()
                },
                enabled = !busy && cookieProvider().isNotBlank() &&
                    fields.all { fieldReady(it, actionParameters[it.key].orEmpty().trim()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("実行") }
        }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    result?.takeIf { it.keys().asSequence().any { key -> key !in setOf("retcode", "message", "data") } }
        ?.let { SectionCard("操作結果") { FeatureResult(it) } }

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
private fun InfoCard(title: String, message: String) {
    SectionCard(title) { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
internal fun SectionCard(title: String, content: @Composable () -> Unit) {
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

private val gachaDateFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.JAPAN)

private fun characterRarity(game: ApiGame, rarity: Int): String =
    if (game == ApiGame.ZZZ) mapOf(5 to "S級", 4 to "A級", 3 to "B級")[rarity] ?: "★$rarity"
    else "★$rarity"
