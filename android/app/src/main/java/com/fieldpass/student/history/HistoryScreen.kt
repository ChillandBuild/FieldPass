package com.fieldpass.student.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldpass.student.R
import com.fieldpass.student.ui.ErrorPanel
import com.fieldpass.student.ui.LoadingBox
import com.fieldpass.student.ui.RecordSummary
import com.fieldpass.student.ui.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh, modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> LoadingBox()
            state.error != null && state.records.isEmpty() -> ErrorPanel(state.error!!, viewModel::refresh)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall) }
                state.error?.let { code -> item { Text(stringResource(code.messageRes), color = MaterialTheme.colorScheme.error) } }
                if (state.records.isEmpty()) item { Text(stringResource(R.string.history_empty)) }
                items(state.records, key = { it.activityId }) { record ->
                    SectionCard {
                        Text(record.activityName, style = MaterialTheme.typography.titleMedium)
                        RecordSummary(record)
                    }
                }
            }
        }
    }
}
