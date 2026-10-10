package com.fieldpass.student.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.error.ErrorParser
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.data.repo.FieldPassRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val records: List<AttendanceRecord> = emptyList(),
    val error: ErrorCode? = null,
)

class HistoryViewModel(private val repository: FieldPassRepository) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state

    init { load(refresh = false) }

    /** Pull to refresh: faculty decisions made since the last load appear here. */
    fun refresh() = load(refresh = true)

    private fun load(refresh: Boolean) {
        _state.update { it.copy(isLoading = !refresh && it.records.isEmpty(), isRefreshing = refresh, error = null) }
        viewModelScope.launch {
            try {
                val records = repository.attendanceHistory()
                _state.update { it.copy(isLoading = false, isRefreshing = false, records = records) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = ErrorParser.fromThrowable(e).code
                _state.update { it.copy(isLoading = false, isRefreshing = false, error = code) }
            }
        }
    }
}
