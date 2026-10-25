package com.salesapp.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatThousands
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.core.formatIsoDate
import com.salesapp.data.OutboxProblem
import com.salesapp.resources.Res
import com.salesapp.resources.problem_clear
import com.salesapp.resources.problem_clear_note
import com.salesapp.resources.problem_cleared
import com.salesapp.resources.problem_clearing
import com.salesapp.resources.problem_file
import com.salesapp.resources.problem_file_value
import com.salesapp.resources.problem_leader
import com.salesapp.resources.problem_no_access
import com.salesapp.resources.problem_note
import com.salesapp.resources.problem_open
import com.salesapp.resources.problem_sent_by
import com.salesapp.resources.problem_sent_value
import com.salesapp.resources.problem_size_bytes
import com.salesapp.resources.problem_size_kb
import com.salesapp.resources.problem_state
import com.salesapp.resources.problem_title
import com.salesapp.resources.problem_website
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MultilineField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun OutboxProblemPage(
    id: Int,
    onBack: () -> Unit,
    viewModel: OutboxProblemViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OutboxProblemScreen(state, viewModel::onEvent, onBack)
}

// What the phone sent and what the office did about it. The file itself is read on the website,
// where it can be opened properly; here Admin says what was entered by hand.
@Composable
fun OutboxProblemScreen(state: OutboxProblemState, onEvent: (OutboxProblemEvent) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.problem_title), onBack = onBack)
        val problem = state.problem
        when {
            !state.allowed -> Say(stringResource(Res.string.problem_no_access), error = true, modifier = Modifier.padding(16.dp))
            state.loading -> LoadingState()
            state.error != null || problem == null -> ErrorState(state.error.orEmpty(), onRetry = { onEvent(OutboxProblemEvent.Retry) })
            else -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ListCard(onClick = null) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(problem.tripNumber, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                            if (problem.route.isNotBlank()) {
                                Text(problem.route, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Fact(stringResource(Res.string.problem_leader), problem.leaderName)
                    Fact(
                        stringResource(Res.string.problem_sent_by),
                        stringResource(Res.string.problem_sent_value, problem.reportedBy, dayOf(problem.reportedAt)),
                    )
                    Fact(stringResource(Res.string.problem_file), fileText(problem))
                    if (problem.note.isNotBlank()) Fact(stringResource(Res.string.problem_note), problem.note)
                    Fact(
                        stringResource(Res.string.problem_state),
                        if (problem.cleared) {
                            stringResource(Res.string.problem_cleared, problem.clearedBy.orEmpty(), dayOf(problem.clearedAt.orEmpty()))
                        } else {
                            stringResource(Res.string.problem_open)
                        },
                    )
                    if (problem.cleared && problem.clearNote.isNotBlank()) {
                        Say(problem.clearNote)
                    }
                    Text(
                        stringResource(Res.string.problem_website),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.actionError?.let { Say(it, error = true) }
                    // Only Admin clears one, and only while it is still open.
                    if (problem.canClear) {
                        MultilineField(
                            label = stringResource(Res.string.problem_clear_note),
                            value = state.note,
                            onValueChange = { onEvent(OutboxProblemEvent.NoteChanged(it)) },
                            enabled = !state.busy,
                        )
                    }
                }
                if (problem.canClear) {
                    PrimaryButton(
                        stringResource(if (state.busy) Res.string.problem_clearing else Res.string.problem_clear),
                        onClick = { onEvent(OutboxProblemEvent.Clear) },
                        enabled = !state.busy,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

// A small grey label with its value under it.
@Composable
private fun Fact(label: String, value: String) {
    if (value.isBlank()) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel(label)
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

// The name of the file and how big it is, so the office knows what to look for on the website.
@Composable
private fun fileText(problem: OutboxProblem): String {
    val size = if (problem.fileSize >= 1024) {
        stringResource(Res.string.problem_size_kb, formatThousands((problem.fileSize / 1024).toLong()))
    } else {
        stringResource(Res.string.problem_size_bytes, formatThousands(problem.fileSize.toLong()))
    }
    return if (problem.fileName.isBlank()) size else stringResource(Res.string.problem_file_value, problem.fileName, size)
}

// A timestamp from the server, shown as the day on this phone.
private fun dayOf(timestamp: String): String =
    if (timestamp.isBlank()) "" else formatIsoDate(localIsoDateFromUtcTimestamp(timestamp))

private val sampleProblem = OutboxProblem(
    id = 5,
    tripId = 2,
    tripNumber = "TRP-000002",
    companyCode = "LV",
    tripStatus = "out",
    route = "Salima and Nkhotakota",
    leaderName = "Chimwemwe Banda",
    fileName = "trip-2-outbox-unreadable-20261025T0900123Z.json",
    fileSize = 4_096,
    reportedBy = "Chimwemwe Banda",
    reportedAt = "2026-10-25T09:05:00.000Z",
)

@Preview
@Composable
private fun OutboxProblemPreview() {
    SalesTheme {
        OutboxProblemScreen(
            OutboxProblemState(loading = false, problem = sampleProblem.copy(canClear = true), note = "Entered the two sales by hand."),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun OutboxProblemClearedPreview() {
    SalesTheme {
        OutboxProblemScreen(
            OutboxProblemState(
                loading = false,
                problem = sampleProblem.copy(
                    cleared = true,
                    clearedBy = "Mary Banda",
                    clearedAt = "2026-10-25T14:00:00.000Z",
                    clearNote = "Both sales entered by hand from the file.",
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
