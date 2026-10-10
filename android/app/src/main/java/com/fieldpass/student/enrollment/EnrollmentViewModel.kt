package com.fieldpass.student.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.error.ErrorParser
import com.fieldpass.student.core.model.Enrollment
import com.fieldpass.student.core.model.EnrollmentStatus
import com.fieldpass.student.core.model.Selfie
import com.fieldpass.student.data.repo.FieldPassRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EnrollmentStep { STATUS, CAMERA, PREVIEW }

data class EnrollmentUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val enrollment: Enrollment? = null,
    val loadError: ErrorCode? = null,
    val step: EnrollmentStep = EnrollmentStep.STATUS,
    val photo: Selfie? = null,
    val guidance: ErrorCode? = null,
    val isSubmitting: Boolean = false,
    val confirmReplace: Boolean = false,
) {
    /** Replacing an existing photo (pending or approved) needs a warning first. */
    val needsReplaceWarning: Boolean
        get() = enrollment?.status == EnrollmentStatus.APPROVED || enrollment?.status == EnrollmentStatus.PENDING
}

class EnrollmentViewModel(private val repository: FieldPassRepository) : ViewModel() {
    private val _state = MutableStateFlow(EnrollmentUiState())
    val state: StateFlow<EnrollmentUiState> = _state

    init { load(refresh = false) }

    fun refresh() = load(refresh = true)

    private fun load(refresh: Boolean) {
        _state.update { it.copy(isLoading = !refresh && it.enrollment == null, isRefreshing = refresh, loadError = null) }
        viewModelScope.launch {
            try {
                val enrollment = repository.enrollment()
                _state.update { it.copy(isLoading = false, isRefreshing = false, enrollment = enrollment) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = ErrorParser.fromThrowable(e).code
                _state.update { it.copy(isLoading = false, isRefreshing = false, loadError = code) }
            }
        }
    }

    fun beginCapture() {
        if (_state.value.needsReplaceWarning) {
            _state.update { it.copy(confirmReplace = true) }
        } else {
            _state.update { it.copy(step = EnrollmentStep.CAMERA, guidance = null) }
        }
    }

    fun confirmReplace() = _state.update { it.copy(confirmReplace = false, step = EnrollmentStep.CAMERA, guidance = null) }

    fun dismissReplace() = _state.update { it.copy(confirmReplace = false) }

    fun onPhoto(jpeg: ByteArray) {
        if (_state.value.step != EnrollmentStep.CAMERA) return
        _state.update { it.copy(photo = Selfie(jpeg), step = EnrollmentStep.PREVIEW, guidance = null) }
    }

    fun onCameraError(code: ErrorCode) = _state.update { it.copy(guidance = code) }

    fun retake() {
        wipePhoto()
        _state.update { it.copy(photo = null, step = EnrollmentStep.CAMERA) }
    }

    fun cancelCapture() {
        wipePhoto()
        _state.update { it.copy(photo = null, step = EnrollmentStep.STATUS, guidance = null) }
    }

    /** Ignored while a submit is in flight, so a double tap cannot upload twice. */
    fun submit() {
        val current = _state.value
        val photo = current.photo ?: return
        if (current.isSubmitting || current.step != EnrollmentStep.PREVIEW) return
        _state.update { it.copy(isSubmitting = true, guidance = null) }
        viewModelScope.launch {
            try {
                val enrollment = repository.submitEnrollment(photo.bytes)
                wipePhoto()
                _state.update {
                    it.copy(isSubmitting = false, enrollment = enrollment, photo = null, step = EnrollmentStep.STATUS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = ErrorParser.fromThrowable(e).code
                val retake = code == ErrorCode.UNUSABLE_CAPTURE || code == ErrorCode.MULTIPLE_FACES || code == ErrorCode.IMAGE_TOO_LARGE
                if (retake) wipePhoto()
                _state.update {
                    it.copy(
                        isSubmitting = false, guidance = code,
                        photo = if (retake) null else it.photo,
                        step = if (retake) EnrollmentStep.CAMERA else EnrollmentStep.PREVIEW,
                    )
                }
            }
        }
    }

    override fun onCleared() = wipePhoto()

    private fun wipePhoto() {
        _state.value.photo?.bytes?.fill(0)
    }
}
