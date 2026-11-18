package com.atuy.hoyodashboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atuy.hoyodashboard.api.DashboardItem
import com.atuy.hoyodashboard.api.HoYoClient
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Dashboard() }
    }
}

@Composable
private fun Dashboard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember { HoYoClient() }
    var linked by remember { mutableStateOf(SecureCookieStore.read(context) != null) }
    var items by remember { mutableStateOf<List<DashboardItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedItem by remember { mutableStateOf<DashboardItem?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            linked = true
            refresh++
        }
    }

    LaunchedEffect(linked, refresh) {
        if (!linked) {
            items = emptyList()
            return@LaunchedEffect
        }
        val cookie = SecureCookieStore.read(context) ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            items = client.dashboard(cookie)
        } catch (exception: Exception) {
            error = exception.message ?: "取得失敗"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(linked) {
        while (linked) {
            delay(5 * 60 * 1000L)
            refresh++
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB8C5FF),
            background = Color(0xFF10141C),
            surface = Color(0xFF1B2230)
        )
    ) {
        Surface(Modifier.fillMaxSize()) {
            val selected = selectedItem
            if (selected != null) {
                GameDetailScreen(
                    item = selected,
                    onBack = { selectedItem = null },
                    onRefresh = { refresh++ }
                )
            } else {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding()
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "HoYoDashboard",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (linked) {
                            Button(onClick = { refresh++ }, enabled = !loading) { Text("更新") }
                        }
                    }
                    if (!linked) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { launcher.launch(Intent(context, LoginActivity::class.java)) }
                        ) { Text("HoYoLABにログイン") }
                    }
                    if (loading) CircularProgressIndicator()
                    if (error != null) Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    if (linked && !loading && error == null && items.isEmpty()) {
                        Text("紐付いたゲームアカウントが見つかりませんでした")
                    }
                    items.forEach { item -> StatusCard(item) { selectedItem = item } }
                    if (linked) {
                        OutlinedButton(
                            onClick = {
                                SecureCookieStore.clear(context)
                                linked = false
                                items = emptyList()
                                selectedItem = null
                                error = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("連携を解除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(item: DashboardItem, onClick: () -> Unit) {
    val game = item.role.game
    val note = item.note
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    game.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text("詳細を見る", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            Text(item.role.nickname + " · " + item.role.uid, style = MaterialTheme.typography.bodySmall)
            if (note == null) {
                Text(item.error ?: "取得失敗", color = MaterialTheme.colorScheme.error)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(game.resource, modifier = Modifier.weight(1f))
                    Text(note.resource.current.toString() + " / " + note.resource.maximum, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress = { note.resource.fraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
                val remaining = note.secondsUntilFull
                if (remaining != null) {
                    Text("満タンまで " + (remaining / 3600) + "時間 " + ((remaining % 3600) / 60) + "分")
                }
                note.daily?.let { daily ->
                    HorizontalDivider()
                    Row {
                        Text("デイリー", modifier = Modifier.weight(1f))
                        Text(daily.current.toString() + " / " + daily.maximum)
                    }
                    if (note.dailyCompleted == true) Text("完了", color = Color(0xFF88D5AA))
                }
                note.details.forEach { detail ->
                    Row {
                        Text(detail.name, modifier = Modifier.weight(1f))
                        Text(detail.value)
                    }
                }
            }
        }
    }
}
