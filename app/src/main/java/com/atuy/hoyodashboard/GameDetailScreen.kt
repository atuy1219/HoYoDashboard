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

@Composable
internal fun GameDetailScreen(
    item: DashboardItem,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val note = item.note
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
            if (note != null) {
                NoteOverview(note, item.role.game.resource)
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230))) {
                    Text(
                        item.error ?: "リアルタイム情報を取得できませんでした",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            UpstreamApiExplorer(item.role)
        }
    }
}

@Composable
private fun NoteOverview(note: DailyNote, resourceLabel: String) {
    var showRaw by remember(note) { mutableStateOf(true) }
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
private fun DetailValueRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
