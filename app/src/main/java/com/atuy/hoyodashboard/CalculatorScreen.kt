package com.atuy.hoyodashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atuy.hoyodashboard.api.japaneseApiError
import com.atuy.hoyodashboard.api.EnhancementCalculator
import com.atuy.hoyodashboard.api.EnhancementPlan
import com.atuy.hoyodashboard.api.FurnishingQuantity
import com.atuy.hoyodashboard.api.LevelUpgrade
import com.atuy.hoyodashboard.api.UpstreamFeatureClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class TargetKind(val label: String, val method: String) {
    CHARACTER("キャラクター", "get_calculator_characters"),
    WEAPON("武器", "get_calculator_weapons"),
    ARTIFACT("聖遺物", "get_calculator_artifacts"),
    TALENT("天賦", "get_character_details"),
    FURNISHING("調度品", "get_calculator_furnishings")
}

private data class CalculatorTarget(
    val kind: TargetKind,
    val id: Int,
    val name: String,
    val icon: String?,
    val level: Int,
    val element: Int?
)

private data class CalculatorEntry(val target: CalculatorTarget, val current: Int, val targetLevel: Int) {
    fun levelUpgrade() = LevelUpgrade(target.id, current, targetLevel)
}

@Composable
internal fun CalculatorScreen(features: UpstreamFeatureClient) {
    val calculator = remember(features) { EnhancementCalculator(features) }
    val scope = rememberCoroutineScope()
    var furnishings by remember(features) { mutableStateOf(false) }
    var replica by remember(features) { mutableStateOf("") }
    var entries by remember(features) { mutableStateOf<List<CalculatorEntry>>(emptyList()) }
    var queued by remember(features) { mutableStateOf<List<List<CalculatorEntry>>>(emptyList()) }
    var picker by remember(features) { mutableStateOf<TargetKind?>(null) }
    var editing by remember(features) { mutableStateOf<CalculatorTarget?>(null) }
    var calculating by remember(features) { mutableStateOf(false) }
    var result by remember(features) { mutableStateOf<JSONObject?>(null) }
    var error by remember(features) { mutableStateOf<String?>(null) }

    fun calculate() {
        val selected = entries
        val plans = queued + listOf(selected).filter { it.isNotEmpty() }
        val code = replica.toLongOrNull()
        calculating = true
        error = null
        result = null
        scope.launch {
            try {
                result = if (furnishings) {
                    calculator.calculateFurnishings(selected.map { FurnishingQuantity(it.target.id, it.targetLevel) }, code)
                } else if (plans.size == 1) {
                    calculator.calculate(plans.single().toPlan())
                } else {
                    calculator.calculateBatch(plans.map { it.toPlan() })
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = japaneseApiError(exception)
            } finally {
                calculating = false
            }
        }
    }

    val character = entries.firstOrNull { it.target.kind == TargetKind.CHARACTER }?.target
    FeatureInput(
        FeatureField("mode", "計算の種類", choices = mapOf("enhancement" to "キャラクター育成", "furnishing" to "調度品製作")),
        if (furnishings) "furnishing" else "enhancement"
    ) { selected ->
        if (!calculating && furnishings != (selected == "furnishing")) {
            furnishings = selected == "furnishing"
            entries = emptyList()
            queued = emptyList()
            result = null
            error = null
        }
    }
    SectionCard(if (furnishings) "調度品" else "育成対象") {
        val kinds = if (furnishings) listOf(TargetKind.FURNISHING) else TargetKind.entries.filter {
            it != TargetKind.FURNISHING && (it != TargetKind.TALENT || character != null)
        }
        FeatureInput(FeatureField("target_kind", "対象を追加", choices = kinds.associate { it.name to it.label }), "") {
            if (!calculating) picker = TargetKind.valueOf(it)
        }
        if (furnishings) FeatureInput(
            FeatureField("share_code", "洞天コピー番号（任意）", required = false, numeric = true),
            replica
        ) { replica = it.trim() }
        entries.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FeatureImage(row.target.icon)
                Column(Modifier.weight(1f)) {
                    Text(row.target.name)
                    Text(
                        if (furnishings) "${row.targetLevel} 個" else "Lv.${row.current} → Lv.${row.targetLevel}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(enabled = !calculating, onClick = {
                    entries = entries.filterNot { it == row ||
                        row.target.kind == TargetKind.CHARACTER && it.target.kind == TargetKind.TALENT }
                }) { Text("削除") }
            }
        }
        if (!furnishings) {
            queued.forEachIndexed { index, plan ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(plan.joinToString("、") { it.target.name }, Modifier.weight(1f))
                    TextButton(enabled = !calculating, onClick = {
                        queued = queued.filterIndexed { position, _ -> position != index }
                    }) { Text("削除") }
                }
            }
            if (entries.isNotEmpty()) TextButton(enabled = !calculating, onClick = {
                queued = queued + listOf(entries)
                entries = emptyList()
            }) { Text("計算に追加して次の対象を選ぶ") }
        }
        Button(
            enabled = !calculating && (entries.isNotEmpty() || queued.isNotEmpty() ||
                furnishings && replica.toLongOrNull()?.let { it > 0 } == true) &&
                (!furnishings || replica.isBlank() || replica.toLongOrNull()?.let { it > 0 } == true),
            onClick = ::calculate,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (calculating) "計算中…" else "素材を計算") }
    }
    error?.let { Text(displayText(it), color = MaterialTheme.colorScheme.error) }
    result?.let { SectionCard("必要素材") { FeatureResult(it, "calculator") } }

    picker?.let { kind ->
        CalculatorPicker(features, kind, character, onClose = { picker = null }) {
            picker = null
            editing = it
        }
    }
    editing?.let { target ->
        val previous = entries.firstOrNull { it.target.kind == target.kind && it.target.id == target.id }
        LevelDialog(target, previous, onClose = { editing = null }) { entry ->
            val previousCharacter = character?.id
            entries = entries.filterNot {
                val kind = entry.target.kind
                it.target.kind == kind && (it.target.id == entry.target.id ||
                    kind in setOf(TargetKind.CHARACTER, TargetKind.WEAPON)) ||
                    kind == TargetKind.CHARACTER && previousCharacter != entry.target.id && it.target.kind == TargetKind.TALENT
            } + entry
            editing = null
        }
    }
}

private fun List<CalculatorEntry>.toPlan() = EnhancementPlan(
    character = firstOrNull { it.target.kind == TargetKind.CHARACTER }?.levelUpgrade(),
    weapon = firstOrNull { it.target.kind == TargetKind.WEAPON }?.levelUpgrade(),
    artifacts = filter { it.target.kind == TargetKind.ARTIFACT }.map(CalculatorEntry::levelUpgrade),
    talents = filter { it.target.kind == TargetKind.TALENT }.map(CalculatorEntry::levelUpgrade),
    element = firstOrNull { it.target.kind == TargetKind.CHARACTER }?.target?.element
)

@Composable
private fun CalculatorPicker(
    features: UpstreamFeatureClient,
    kind: TargetKind,
    character: CalculatorTarget?,
    onClose: () -> Unit,
    onSelect: (CalculatorTarget) -> Unit
) {
    var rows by remember(kind) { mutableStateOf<List<CalculatorTarget>?>(null) }
    var error by remember(kind) { mutableStateOf<String?>(null) }
    var query by remember(kind) { mutableStateOf("") }
    var artifactSlot by remember(kind) { mutableStateOf("1") }
    LaunchedEffect(kind, artifactSlot, character) {
        rows = null
        error = null
        val args = when (kind) {
            TargetKind.TALENT -> mapOf("avatar_id" to checkNotNull(character).id.toString())
            TargetKind.ARTIFACT -> mapOf("reliquary_cat_id" to artifactSlot)
            else -> emptyMap()
        }
        try {
            val response = features.raw(kind.method, args)
            val list = response.optJSONArray(if (kind == TargetKind.TALENT) "skill_list" else "list") ?: JSONArray()
            rows = (0 until list.length()).mapNotNull { index ->
                val item = list.optJSONObject(index) ?: return@mapNotNull null
                val id = if (kind == TargetKind.TALENT) item.optInt("group_id") else item.optInt("id")
                if (id <= 0 || kind == TargetKind.TALENT && item.optInt("max_level") <= 1) return@mapNotNull null
                val name = item.optString("name").ifBlank { item.optString("skill_name") }
                if (name.isBlank()) return@mapNotNull null
                CalculatorTarget(
                    kind, id, name,
                    item.optString("icon").ifBlank { item.optString("icon_url") },
                    item.optInt("level_current"),
                    item.optInt("element_attr_id").takeIf { it > 0 }
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = japaneseApiError(exception)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(kind.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (kind == TargetKind.ARTIFACT) {
                    val slots = mapOf("1" to "生の花", "2" to "死の羽", "3" to "時の砂", "4" to "空の杯", "5" to "理の冠")
                    FeatureInput(FeatureField("slot", "部位", choices = slots), artifactSlot) { artifactSlot = it }
                }
                OutlinedTextField(query, { query = it }, label = { Text("名前で検索") }, singleLine = true)
                val matches = rows?.filter { it.name.contains(query.trim(), ignoreCase = true) }
                when {
                    error != null -> Text(displayText(error.orEmpty()), color = MaterialTheme.colorScheme.error)
                    matches == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    matches.isEmpty() -> Text("該当する対象がありません")
                    else -> LazyColumn(Modifier.heightIn(max = 365.dp)) {
                        items(matches) { target ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onSelect(target) }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FeatureImage(target.icon)
                                Text(target.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("閉じる") } }
    )
}

@Composable
private fun LevelDialog(
    target: CalculatorTarget,
    previous: CalculatorEntry?,
    onClose: () -> Unit,
    onAdd: (CalculatorEntry) -> Unit
) {
    val furnishing = target.kind == TargetKind.FURNISHING
    val minimum = if (target.kind == TargetKind.ARTIFACT) 0 else 1
    val maximum = when (target.kind) {
        TargetKind.TALENT -> 10
        TargetKind.ARTIFACT -> 20
        TargetKind.FURNISHING -> 9_999
        else -> 100
    }
    var current by remember(target) { mutableStateOf((previous?.current ?: target.level.coerceAtLeast(minimum)).toString()) }
    var goal by remember(target) {
        mutableStateOf((previous?.targetLevel ?: if (furnishing) 1 else minOf(90, maximum)).toString())
    }
    val currentLevel = if (furnishing) 0 else current.toIntOrNull() ?: -1
    val targetLevel = goal.toIntOrNull() ?: -1
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(target.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FeatureImage(target.icon)
                if (!furnishing) FeatureInput(FeatureField("current", "現在のレベル", numeric = true), current) { current = it }
                FeatureInput(
                    FeatureField("target", if (furnishing) "必要個数" else "目標レベル", numeric = true),
                    goal
                ) { goal = it }
            }
        },
        confirmButton = {
            Button(
                enabled = targetLevel in 1..maximum && (furnishing || currentLevel in minimum until targetLevel),
                onClick = { onAdd(CalculatorEntry(target, currentLevel, targetLevel)) }
            ) { Text("追加") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("キャンセル") } }
    )
}
