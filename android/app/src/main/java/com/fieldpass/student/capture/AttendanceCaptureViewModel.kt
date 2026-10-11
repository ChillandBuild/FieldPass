package com.fieldpass.student.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.error.ErrorParser
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Selfie
import com.fieldpass.student.core.model.SubmitEvidence
import com.fieldpass.student.core.time.AttemptCountdown
import com.fieldpass.student.data.device.DeviceIntegritySource
import com.fieldpass.student.data.repo.FieldPassRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * State machine for one attendance attempt:
 * create attempt -> fresh location -> optional faculty-screen code -> selfie -> submit against the attempt ID
 * -> show the SERVER's result.
 *
 * The code step is an extra signal only: skipping it, or any failure in it, never blocks the selfie and submit.
 * It has no timer of its own; the attempt's single countdown keeps running underneath it.
 *
 * The same attempt ID is reused for every retry (lost response, retake, stale location).
 * A new attempt is created only by [startOver] after the user acknowledges expiry.
 */
class AttendanceCaptureViewModel(
    private val activityId: String,
    private val action: Action,
    private val repository: FieldPassRepository,
    private val location: LocationProvider,
    private val integrity: DeviceIntegritySource,
    private val elapsedRealtime: () -> Long,
    private val tickerEnabled: Boolean = true,
    private val hasBackCamera: Boolean = true,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state

    private var started = false
    private var countdown: AttemptCountdown? = null
    private var attemptJob: Job? = null
    private var locationJob: Job? = null
    private var submitJob: Job? = null
    private var tickerJob: Job? = null
    private var presenceJob: Job? = null
    private var codes = PresenceCodeDebounce()

    val actionType: Action get() = action

    /** Idempotent: safe to call from a composable that may be re-entered after rotation. */
    fun start() {
        if (started) return
        started = true
        attemptJob = viewModelScope.launch {
            try {
                val attempt = repository.createAttempt(activityId, action)
                countdown = AttemptCountdown.fromServerTimes(attempt.createdAt, attempt.expiresAt, elapsedRealtime())
                _state.update {
                    it.copy(attempt = attempt, remainingMillis = countdown?.remainingMillis(elapsedRealtime()))
                }
                startTicker()
                fetchLocation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(ErrorParser.fromThrowable(e).code)
            }
        }
    }

    /** After expiry: forget everything and create a NEW attempt. */
    fun startOver() {
        cancelJobs()
        clearSelfie()
        countdown = null
        started = false
        codes = PresenceCodeDebounce()
        _state.value = CaptureUiState()
        start()
    }

    /** "Try again for a better fix", and retry after a location failure. Same attempt. */
    fun retryLocation() {
        val phase = _state.value.phase
        if (phase != CapturePhase.LOCATION_FAILED && phase != CapturePhase.POOR_ACCURACY) return
        fetchLocation()
    }

    /** "Submit anyway (may need faculty review)" after a poor-accuracy fix. */
    fun acceptPoorAccuracy() {
        if (_state.value.phase != CapturePhase.POOR_ACCURACY) return
        _state.update { it.copy(poorAccuracyAccepted = true, phase = afterLocationPhase(it), error = null) }
    }

    /**
     * One decoded QR string from the scanner, which fires many times per second. Anything that is not a
     * FieldPass code, anything already sent, and anything arriving while a check is running is dropped here.
     */
    fun onCodeScanned(raw: String) {
        val attempt = _state.value.attempt ?: return
        if (_state.value.phase != CapturePhase.AWAITING_CODE || presenceJob?.isActive == true) return
        val code = codes.next(raw) ?: return
        _state.update { it.copy(codeStatus = CodeStatus.CHECKING, error = null) }
        presenceJob = viewModelScope.launch {
            try {
                onPresenceChecked(repository.submitPresenceCode(attempt.id, code))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                codes.sendFailed(code)
                onPresenceError(ErrorParser.fromThrowable(e))
            }
        }
    }

    /** "Skip", and also what the screen calls when the back camera cannot start: straight on to the selfie. */
    fun skipCode() {
        if (_state.value.phase != CapturePhase.AWAITING_CODE) return
        presenceJob?.cancel()
        _state.update { it.copy(phase = CapturePhase.AWAITING_SELFIE, codeStepDone = true, codeStatus = CodeStatus.SCANNING, error = null) }
    }

    /** Photo A (looking straight) and photo B (head turned the way the server asked), taken together. */
    fun onPhotosCaptured(selfie: ByteArray, turnPhoto: ByteArray) {
        val current = _state.value
        if (current.phase != CapturePhase.AWAITING_SELFIE && current.phase != CapturePhase.READY_TO_SUBMIT) return
        if (current.fix == null) return
        _state.update {
            it.copy(selfie = Selfie(selfie), turnPhoto = Selfie(turnPhoto), phase = CapturePhase.READY_TO_SUBMIT, error = null)
        }
    }

    fun onCameraError(code: ErrorCode) {
        if (_state.value.phase == CapturePhase.AWAITING_SELFIE) _state.update { it.copy(error = code) }
    }

    fun retakeSelfie() {
        val current = _state.value
        if (current.phase != CapturePhase.READY_TO_SUBMIT || current.pendingReplay) return
        _state.update { it.copy(selfie = null, turnPhoto = null, phase = CapturePhase.AWAITING_SELFIE) }
    }

    /** Ignored unless ready; the phase flips to SUBMITTING synchronously so duplicate taps do nothing. */
    fun submit() {
        val current = _state.value
        if (current.phase != CapturePhase.READY_TO_SUBMIT) return
        val attempt = current.attempt ?: return
        val fix = current.fix ?: return
        val selfie = current.selfie ?: return
        val turnPhoto = current.turnPhoto ?: return
        _state.update { it.copy(phase = CapturePhase.SUBMITTING, error = null) }
        submitJob = viewModelScope.launch {
            try {
                val event = repository.submitAttempt(attempt, SubmitEvidence(selfie.bytes, turnPhoto.bytes, fix, integrity.flags()))
                clearSelfie()
                stopTicker()
                _state.update {
                    it.copy(phase = CapturePhase.DONE, result = event, selfie = null, turnPhoto = null, pendingReplay = false, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onSubmitError(ErrorParser.fromThrowable(e))
            }
        }
    }

    /** Called by the countdown ticker and by tests. Never interrupts an in-flight or replayed submit. */
    fun tick() {
        val c = countdown ?: return
        val remaining = c.remainingMillis(elapsedRealtime())
        val current = _state.value
        val expirable = !current.pendingReplay && current.phase !in NON_EXPIRABLE
        if (remaining <= 0 && expirable) {
            expire()
        } else {
            _state.update { it.copy(remainingMillis = remaining) }
        }
    }

    /** User left the screen or cancelled: drop captured bytes and stop work. */
    fun discard() {
        cancelJobs()
        clearSelfie()
        _state.update { it.copy(selfie = null, turnPhoto = null) }
    }

    override fun onCleared() {
        cancelJobs()
        clearSelfie()
    }

    private fun fetchLocation() {
        locationJob?.cancel()
        _state.update { it.copy(phase = CapturePhase.LOCATING) }
        locationJob = viewModelScope.launch {
            try {
                val fix = location.freshFix()
                _state.update {
                    val poor = fix.isPoorAccuracy && !it.poorAccuracyAccepted
                    val withFix = it.copy(fix = fix, error = if (poor) it.error else null)
                    withFix.copy(phase = if (poor) CapturePhase.POOR_ACCURACY else afterLocationPhase(withFix))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = ErrorParser.fromThrowable(e).code
                _state.update { it.copy(phase = CapturePhase.LOCATION_FAILED, error = code) }
            }
        }
    }

    private fun afterLocationPhase(s: CaptureUiState) = when {
        s.selfie != null -> CapturePhase.READY_TO_SUBMIT
        hasBackCamera && !s.codeStepDone -> CapturePhase.AWAITING_CODE
        else -> CapturePhase.AWAITING_SELFIE
    }

    private fun onPresenceChecked(verified: Boolean) {
        if (_state.value.phase != CapturePhase.AWAITING_CODE) return
        _state.update {
            if (verified) {
                it.copy(phase = CapturePhase.AWAITING_SELFIE, codeVerified = true, codeStepDone = true, codeStatus = CodeStatus.SCANNING, error = null)
            } else {
                it.copy(codeStatus = CodeStatus.TOO_OLD)
            }
        }
    }

    /** Only an expired attempt ends the step; anything else just shows a message while Skip and re-scanning stay open. */
    private fun onPresenceError(e: AppError) {
        if (_state.value.phase != CapturePhase.AWAITING_CODE) return
        if (e.code == ErrorCode.ATTEMPT_EXPIRED) {
            expire()
        } else {
            _state.update { it.copy(codeStatus = CodeStatus.FAILED, error = e.code) }
        }
    }

    private fun onSubmitError(e: AppError) {
        when {
            e.isLostResponse ->
                _state.update { it.copy(phase = CapturePhase.READY_TO_SUBMIT, pendingReplay = true, error = e.code) }
            e.code == ErrorCode.UNUSABLE_CAPTURE || e.code == ErrorCode.MULTIPLE_FACES ||
                e.code == ErrorCode.FACE_NOT_MATCHED -> {
                clearSelfie()
                _state.update {
                    it.copy(phase = CapturePhase.AWAITING_SELFIE, selfie = null, turnPhoto = null, pendingReplay = false, error = e.code)
                }
            }
            e.code == ErrorCode.LOCATION_STALE -> {
                _state.update { it.copy(pendingReplay = false, error = e.code) }
                fetchLocation()
            }
            e.code == ErrorCode.ATTEMPT_EXPIRED -> expire()
            else -> fail(e.code)
        }
    }

    private fun expire() {
        presenceJob?.cancel()
        stopTicker()
        clearSelfie()
        _state.update {
            it.copy(phase = CapturePhase.EXPIRED, selfie = null, turnPhoto = null, remainingMillis = 0, pendingReplay = false, error = null)
        }
    }

    private fun fail(code: ErrorCode) {
        presenceJob?.cancel()
        stopTicker()
        clearSelfie()
        _state.update { it.copy(phase = CapturePhase.ATTEMPT_FAILED, selfie = null, turnPhoto = null, error = code) }
    }

    private fun startTicker() {
        if (!tickerEnabled) return
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                tick()
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
    }

    private fun cancelJobs() {
        attemptJob?.cancel()
        locationJob?.cancel()
        submitJob?.cancel()
        tickerJob?.cancel()
        presenceJob?.cancel()
    }

    /** Best-effort wipe of the in-memory JPEG; the reference is also dropped from state. */
    private fun clearSelfie() {
        _state.value.selfie?.bytes?.fill(0)
        _state.value.turnPhoto?.bytes?.fill(0)
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        val NON_EXPIRABLE = setOf(CapturePhase.SUBMITTING, CapturePhase.DONE, CapturePhase.EXPIRED, CapturePhase.ATTEMPT_FAILED)
    }
}
