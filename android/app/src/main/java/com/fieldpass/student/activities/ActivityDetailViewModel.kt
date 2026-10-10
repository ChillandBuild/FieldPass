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

data class ActivityDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val activity: Activity? = null,
    /** Null while unknown (e.g. the lookup failed); the server still enforces the gate in that case. */
    val enrollmentStatus: EnrollmentStatus? = null,
    val error: ErrorCode? = null,
) {
    /** The server's next_actions ignores enrollment, so the app sends unapproved students to the photo step. */
    val needsEnrollment: Boolean get() = enrollmentStatus != null && enrollmentStatus != EnrollmentStatus.APPROVED
}

class ActivityDetailViewModel(
    private val activityId: String,
    private val repository: FieldPassRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ActivityDetailUiState())
    val state: StateFlow<ActivityDetailUiState> = _state

    init { load(refresh = false) }

    /** Called on refresh and when returning from an attendance attempt so `next_actions` is current. */
    fun refresh() = load(refresh = true)

    private fun load(refresh: Boolean) {
        _state.update { it.copy(isLoading = !refresh && it.activity == null, isRefreshing = refresh, error = null) }
        viewModelScope.launch {
            try {
                val activity = repository.activity(activityId)
                val enrollment = try {
                    repository.enrollment().status
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, activity = activity, enrollmentStatus = enrollment)
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
