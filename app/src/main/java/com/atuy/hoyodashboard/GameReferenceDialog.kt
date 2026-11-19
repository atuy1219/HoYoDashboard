package com.atuy.hoyodashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

private sealed interface ReferenceState {
    data object Loading : ReferenceState
    data object Missing : ReferenceState
    data object Failed : ReferenceState
    data class Ready(val entry: GameReferenceEntry) : ReferenceState
}

@Composable
internal fun GameReferenceDialog(
    reference: GameTextReference,
    localEntry: GameReferenceEntry?,
    onDismiss: () -> Unit
) {
    key(reference.id, reference.label) {
        var retry by remember { mutableIntStateOf(0) }
        val state by produceState<ReferenceState>(
            localEntry?.let { ReferenceState.Ready(it) } ?: ReferenceState.Loading, localEntry, retry
        ) {
            value = if (localEntry != null) ReferenceState.Ready(localEntry) else {
                value = ReferenceState.Loading
                try {
                    gameReferenceRepository.get(reference.id)?.let { ReferenceState.Ready(it) } ?: ReferenceState.Missing
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    ReferenceState.Failed
                }
            }
        }
        val current = state
        val title = (current as? ReferenceState.Ready)?.entry?.title ?: reference.label.ifBlank { "参照先" }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(displayText(title)) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (current) {
                        ReferenceState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        ReferenceState.Missing -> Text("参照先の説明データがありません。")
                        ReferenceState.Failed -> {
                            Text("説明を取得できませんでした。")
                            TextButton(onClick = { retry++ }) { Text("再試行") }
                        }
                        is ReferenceState.Ready -> {
                            FeatureImage(current.entry.icon)
                            JapaneseContent(current.entry.description)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
        )
    }
}
