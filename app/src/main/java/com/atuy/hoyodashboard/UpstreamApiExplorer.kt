package com.atuy.hoyodashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atuy.hoyodashboard.api.ApiAccount
import com.atuy.hoyodashboard.api.ApiMethod
import com.atuy.hoyodashboard.api.ApiGame
import com.atuy.hoyodashboard.api.Role
import com.atuy.hoyodashboard.api.UpstreamCatalog
import com.atuy.hoyodashboard.api.UpstreamApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private sealed interface LoadState {
    data object Fetching : LoadState
    data class Ready(val json: String) : LoadState
    data class Failed(val message: String) : LoadState
}

@Composable
internal fun UpstreamApiExplorer(role: Role) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember { UpstreamApiClient() }
    val account = remember(role) { ApiAccount.from(role) }
    val methods = remember(role.game) { client.available(ApiGame.from(role.game)) }
    val categories = remember(methods) { methods.groupBy { it.category } }
    val coroutineScope = rememberCoroutineScope()
    val permits = remember { Semaphore(2) }
    var states by remember(role.uid) { mutableStateOf<Map<String, LoadState>>(emptyMap()) }
    var openedCategory by remember { mutableStateOf<String?>(null) }
    var selectedResult by remember { mutableStateOf<String?>(null) }
    var parameterMethod by remember { mutableStateOf<ApiMethod?>(null) }
    var parameterText by remember { mutableStateOf("") }

    suspend fun fetch(method: ApiMethod, arguments: Map<String, String> = emptyMap()) {
        val cookie = SecureCookieStore.read(context)
        if (cookie.isNullOrBlank()) {
            states = states + (method.name to LoadState.Failed("HoYoLABにログインしてください"))
            return
        }
        states = states + (method.name to LoadState.Fetching)
        try {
            permits.withPermit {
                val result = client.call(cookie, account, method, arguments)
                states = states + (method.name to LoadState.Ready(result.toString(2)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            states = states + (method.name to LoadState.Failed(e.message ?: "取得失敗"))
        }
    }

    LaunchedEffect(role.uid, role.game) {
        val automatic = methods.filter {
            it.readOnly && it.required.isEmpty() && !it.authKeyRequired &&
                it.name !in setOf("get_genshin_notes", "get_starrail_notes", "get_zzz_notes")
        }
        automatic.forEach { method ->
            launch {
                fetch(method)
                delay(120)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "genshin.py API",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            val successes = states.values.count { it is LoadState.Ready }
            Text(successes.toString() + " / " + methods.size)
        }

        categories.forEach { (category, entries) ->
            val expanded = openedCategory == category
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2230))
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { openedCategory = if (expanded) null else category }
                            .padding(16.dp)
                    ) {
                        Text(
                            category,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold
                        )
                        val ready = entries.count { states[it.name] is LoadState.Ready }
                        Text(ready.toString() + "/" + entries.size + if (expanded) "  −" else "  +")
                    }
                    if (expanded) {
                        entries.forEach { method ->
                            val status = states[method.name]
                            HorizontalDivider(color = Color(0xFF353B48))
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        when {
                                            method.authKeyRequired || method.required.isNotEmpty() -> {
                                                parameterMethod = method
                                                parameterText = ""
                                            }
                                            else -> coroutineScope.launch { fetch(method) }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Row {
                                    Text(method.title, modifier = Modifier.weight(1f))
                                    val label = when (status) {
                                        null -> if (method.required.isNotEmpty() || method.authKeyRequired) "入力が必要" else "未取得"
                                        LoadState.Fetching -> "取得中"
                                        is LoadState.Ready -> "取得済"
                                        is LoadState.Failed -> "取得失敗"
                                    }
                                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                }
                                if (status is LoadState.Ready) {
                                    TextButton(onClick = { selectedResult = method.name }) { Text("データを見る") }
                                } else if (status is LoadState.Failed) {
                                    Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val currentMethod = parameterMethod
    if (currentMethod != null) {
        val keys = currentMethod.required + if (currentMethod.authKeyRequired) setOf("authkey") else emptySet()
        AlertDialog(
            onDismissRequest = { parameterMethod = null },
            title = { Text(currentMethod.title) },
            text = {
                Column {
                    Text("必要なパラメータ: " + keys.joinToString(", "))
                    OutlinedTextField(
                        value = parameterText,
                        onValueChange = { parameterText = it },
                        label = { Text("key=value（1行につき1件）") },
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val params = parameterText.lines().mapNotNull {
                        val index = it.indexOf('=')
                        if (index <= 0) null else it.substring(0, index).trim() to it.substring(index + 1).trim()
                    }.toMap()
                    parameterMethod = null
                    coroutineScope.launch { fetch(currentMethod, params) }
                }) { Text("取得") }
            },
            dismissButton = { TextButton(onClick = { parameterMethod = null }) { Text("キャンセル") } }
        )
    }

    val resultKey = selectedResult
    if (resultKey != null) {
        val result = states[resultKey]
        AlertDialog(
            onDismissRequest = { selectedResult = null },
            title = { Text(UpstreamCatalog.find(resultKey)?.title ?: resultKey) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        if (result is LoadState.Ready) result.json else if (result is LoadState.Failed) result.message
                        else "利用できるレスポンスがありません",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = { selectedResult = null }) { Text("閉じる") } }
        )
    }
}
