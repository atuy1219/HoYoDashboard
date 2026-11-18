package com.atuy.hoyodashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atuy.hoyodashboard.api.ApiMethod
import com.atuy.hoyodashboard.api.UpstreamFeatureClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class FeatureRequest(val method: ApiMethod, val arguments: Map<String, String>)
private data class FeaturePage(
    val request: FeatureRequest,
    val result: JSONObject? = null,
    val error: String? = null
)

@Composable
internal fun FeatureDirectory(features: UpstreamFeatureClient, categoryFilter: String? = null) {
    val scope = rememberCoroutineScope()
    val game = features.account.game
    val methods = remember(features, categoryFilter) {
        features.availableMethods().filter {
            if (categoryFilter != null) it.category == categoryFilter
            else it.category != "戦闘記録" &&
                it.name !in setOf("calculate_enhancement", "calculate_batch_enhancement", "calculate_furnishings")
        }
    }
    val categories = remember(methods) { methods.groupBy(ApiMethod::category) }
    var category by remember(features, categoryFilter) { mutableStateOf(categoryFilter) }
    var formMethod by remember(features) { mutableStateOf<ApiMethod?>(null) }
    var arguments by remember(features) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var stack by remember(features) { mutableStateOf<List<FeaturePage>>(emptyList()) }
    var loading by remember(features) { mutableStateOf<Job?>(null) }

    fun open(method: ApiMethod, values: Map<String, String>, replace: Boolean = false) {
        val request = FeatureRequest(method, values)
        val index = if (replace) stack.lastIndex else stack.size
        stack = stack.take(index) + FeaturePage(request)
        loading?.cancel()
        loading = scope.launch {
            val page = try {
                val params = if (method.name in setOf("wish_history", "warp_history", "signal_history", "mw_wish_history",
                        "chronicle_signal_history", "transaction_log", "get_lineups", "get_tcg_decks", "teapot_replicas"
                    )) mapOf("limit" to "100") + values else values
                FeaturePage(request, result = features.raw(method.name, params))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                FeaturePage(request, error = exception.message ?: "取得できませんでした")
            }
            if (stack.getOrNull(index)?.request == request) {
                stack = stack.toMutableList().apply { set(index, page) }
            }
        }
    }

    fun dismissPage() {
        loading?.cancel()
        stack = stack.dropLast(1)
    }

    categories.forEach { (label, entries) ->
        val expanded = category == label
        Card(Modifier.fillMaxWidth()) {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { category = if (expanded) null else label }.padding(16.dp)
                ) {
                    Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(if (expanded) "−" else "+")
                }
                if (expanded) entries.forEach { method ->
                    HorizontalDivider()
                    Text(
                        method.title,
                        Modifier.fillMaxWidth().clickable {
                            val fields = featureFields(method, game, features.account.region)
                            if (fields.isEmpty()) open(method, emptyMap())
                            else {
                                arguments = method.defaults.filterKeys { key ->
                                    fields.any { it.key == key }
                                }
                                formMethod = method
                            }
                        }.padding(horizontal = 16.dp, vertical = 13.dp)
                    )
                }
            }
        }
    }

    formMethod?.takeIf { it.name == "get_starrail_lineups" }?.let { method ->
        StarRailLineupForm(features, onClose = { formMethod = null }) { values ->
            formMethod = null
            open(method, values)
        }
    }

    formMethod?.takeUnless { it.name == "get_starrail_lineups" }?.let { method ->
        val fields = remember(method) { featureFields(method, game, features.account.region) }
        AlertDialog(
            onDismissRequest = { formMethod = null },
            title = { Text(method.title) },
            text = {
                Column(
                    Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    fields.forEach { field ->
                        FeatureInput(field, arguments[field.key].orEmpty()) {
                            arguments = arguments + (field.key to it)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = fields.all { fieldReady(it, arguments[it.key].orEmpty().trim()) },
                    onClick = {
                        val values = prepareArguments(method, arguments)
                        formMethod = null
                        open(method, values)
                    }
                ) { Text("表示") }
            },
            dismissButton = { TextButton(onClick = { formMethod = null }) { Text("キャンセル") } }
        )
    }

    stack.lastOrNull()?.let { page ->
        val (method, values) = page.request
        val result = page.result
        val onNext = if (result == null) null else nextArguments(page.request, result)
        AlertDialog(
            onDismissRequest = ::dismissPage,
            title = { Text(method.title) },
            text = {
                androidx.compose.runtime.key(page.request) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when {
                            result != null -> FeatureResult(result, method.name) { link ->
                                val target = methods.firstOrNull { it.name == link.method }
                                if (target != null) open(target, prepareArguments(target, link.arguments))
                            }
                            page.error != null -> Text(displayText(page.error), color = MaterialTheme.colorScheme.error)
                            else -> CircularProgressIndicator()
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    page.error != null -> TextButton(onClick = { open(method, values, replace = true) }) { Text("再試行") }
                    onNext != null -> TextButton(onClick = { open(method, onNext) }) { Text("次のページ") }
                    else -> TextButton(onClick = ::dismissPage) { Text(if (stack.size > 1) "戻る" else "閉じる") }
                }
            },
            dismissButton = {
                if (page.error != null || onNext != null) {
                    TextButton(onClick = ::dismissPage) { Text(if (stack.size > 1) "戻る" else "閉じる") }
                }
            }
        )
    }
}

private fun nextArguments(request: FeatureRequest, result: JSONObject): Map<String, String>? {
    if (request.method.name == "get_accompany_character_posts") {
        if (result.optBoolean("is_last") || (result.optJSONArray("list")?.length() ?: 0) == 0) return null
        val keys = listOf("last_id", "client_param", "session_param")
        val tokens = keys.associateWith { result.optString(it) }
        if (tokens.all { (key, value) -> value == request.arguments[key].orEmpty() }) return null
        return request.arguments + tokens + ("is_first_brush" to "false")
    }
    if (request.method.name == "get_zzz_diary_detail" || request.method.name == "get_mimo_point_history") {
        val field = if (request.method.name == "get_zzz_diary_detail") "current_page" else "page"
        val page = request.arguments[field]?.toIntOrNull() ?: 1
        val size = request.arguments["page_size"]?.toIntOrNull() ?: 20
        val count = result.optJSONArray("list")?.length() ?: 0
        val total = result.optInt("total", -1)
        if (count == 0 || if (total >= 0) page.toLong() * size >= total else count < size) return null
        return request.arguments + (field to (page + 1).toString())
    }
    if (request.method.name == "get_starrail_lineups") {
        val token = result.optString("next_page_token")
        if (token.isBlank() || token == request.arguments["next_page_token"]) return null
        return request.arguments + ("next_page_token" to token)
    }
    return null
}
