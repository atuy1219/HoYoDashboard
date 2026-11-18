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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atuy.hoyodashboard.api.DailyNote
import com.atuy.hoyodashboard.api.DashboardItem
import com.atuy.hoyodashboard.api.Game

private data class FeatureGroup(val title: String, val features: List<String>)

@Composable
internal fun GameDetailScreen(
    item: DashboardItem,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val groups = remember(item.role.game) { featureGroups(item.role.game) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("戻る") }
            Text(
                item.role.game.label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRefresh) { Text("更新") }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                item.role.nickname + " · " + item.role.uid,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.note != null) {
                NoteOverview(item.note)
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230))) {
                    Text(
                        item.error ?: "リアルタイム情報を取得できませんでした",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            groups.forEach { group -> FeatureGroupCard(group) }
        }
    }
}

@Composable
private fun NoteOverview(note: DailyNote) {
    var showRaw by remember(note) { mutableStateOf(false) }
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
                Text("スタミナ", modifier = Modifier.weight(1f))
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
                DetailValueRow("満タンまで", (seconds / 3600).toString() + "時間 " + ((seconds % 3600) / 60) + "分")
            }
            note.daily?.let { DetailValueRow("デイリー", it.current.toString() + " / " + it.maximum) }
            note.dailyCompleted?.let { DetailValueRow("デイリー報酬", if (it) "受取済み" else "未受取") }
            note.details.forEach { DetailValueRow(it.name, it.value) }
            if (note.rawFields.isNotEmpty()) {
                HorizontalDivider(color = Color(0xFF394252))
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "APIフィールドを閉じる" else "APIフィールド " + note.rawFields.size + " 件")
                }
                if (showRaw) {
                    note.rawFields.forEach { DetailValueRow(it.name, it.value) }
                }
            }
        }
    }
}

@Composable
private fun FeatureGroupCard(group: FeatureGroup) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            group.features.forEachIndexed { index, feature ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(feature, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (feature == "リアルタイムノート") "取得中" else "API未移植",
                        color = if (feature == "リアルタイムノート") Color(0xFF88D5AA) else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (index != group.features.lastIndex) {
                    HorizontalDivider(color = Color(0xFF394252))
                }
            }
        }
    }
}

@Composable
private fun DetailValueRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun featureGroups(game: Game): List<FeatureGroup> = when (game) {
    Game.GENSHIN -> listOf(
        FeatureGroup("リアルタイム", listOf("リアルタイムノート")),
        FeatureGroup("プロフィール・キャラクター", listOf(
            "プレイヤー情報", "キャラクター一覧", "キャラクター詳細・装備・聖遺物", "上位キャラクター設定"
        )),
        FeatureGroup("戦闘記録", listOf(
            "深境螺旋", "幻想シアター", "幽境の激戦", "追憶のエコー", "活動履歴"
        )),
        FeatureGroup("イベント・七聖召喚", listOf(
            "イベントカレンダー", "七聖召喚プレビュー・対戦記録", "カード・デッキ・カード効果"
        )),
        FeatureGroup("育成・攻略", listOf(
            "キャラクター・武器・聖遺物の育成計算", "調度品計算", "チーム編成", "塵歌壺レプリカ", "Wiki"
        )),
        FeatureGroup("履歴・HoYoLAB", listOf(
            "原石・モラ等の月次記録", "祈願履歴", "取引履歴", "デイリー報酬", "お知らせ・Webイベント"
        ))
    )
    Game.STAR_RAIL -> listOf(
        FeatureGroup("リアルタイム", listOf("リアルタイムノート")),
        FeatureGroup("プロフィール・キャラクター", listOf(
            "プレイヤー情報", "キャラクター一覧・詳細", "編成シミュレータ"
        )),
        FeatureGroup("戦闘・模擬宇宙", listOf(
            "忘却の庭", "虚構叙事", "末日の幻影", "模擬宇宙・階差宇宙", "異相演算"
        )),
        FeatureGroup("イベント・履歴", listOf(
            "イベントカレンダー", "開拓紀行・月次記録", "ワープ履歴", "取引履歴"
        )),
        FeatureGroup("HoYoLAB", listOf("デイリー報酬", "お知らせ・Webイベント", "Wiki"))
    )
    Game.ZZZ -> listOf(
        FeatureGroup("リアルタイム", listOf("リアルタイムノート")),
        FeatureGroup("プロフィール・エージェント", listOf(
            "プレイヤー情報", "エージェント一覧・詳細", "ボンプ一覧", "育成ガイド"
        )),
        FeatureGroup("戦闘記録", listOf(
            "式輿防衛", "危局強襲", "零号ホロウ", "閾値シミュレーション"
        )),
        FeatureGroup("イベント・履歴", listOf(
            "イベントカレンダー", "ガチャカレンダー・提供割合", "変調履歴", "スクラッチ・ビデオ屋日誌"
        )),
        FeatureGroup("HoYoLAB", listOf("デイリー報酬", "お知らせ・Webイベント"))
    )
}
