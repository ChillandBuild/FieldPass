package com.fieldpass.student.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.error.ErrorParser
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.EnrollmentStatus
import com.fieldpass.student.data.repo.FieldPassRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActivitiesUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val activities: List<Activity> = emptyList(),
    val enrollmentStatus: EnrollmentStatus? = null,
    val error: ErrorCode? = null,
)

class ActivitiesViewModel(private val repository: FieldPassRepository) : ViewModel() {
    private val _state = MutableStateFlow(ActivitiesUiState())
    val state: StateFlow<ActivitiesUiState> = _state

    init { load(refresh = false) }

    fun refresh() = load(refresh = true)

    private fun load(refresh: Boolean) {
        _state.update { it.copy(isLoading = !refresh && it.activities.isEmpty(), isRefreshing = refresh, error = null) }
        viewModelScope.launch {
            try {
                val activities = repository.activities()
                val enrollment = try {
                    repository.enrollment().status
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, activities = activities, enrollmentStatus = enrollment)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = ErrorParser.fromThrowable(e).code
                _state.update { it.copy(isLoading = false, isRefreshing = false, error = code) }
            }
        }
    }
}
