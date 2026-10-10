package com.fieldpass.student.capture

import com.fieldpass.student.ScriptedLocation
import com.fieldpass.student.StubRepository
import com.fieldpass.student.attempt
import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.AttendanceEvent
import com.fieldpass.student.core.model.AttendanceStatus
import com.fieldpass.student.core.model.Attempt
import com.fieldpass.student.core.model.DeviceFlags
import com.fieldpass.student.core.model.LocationFix
import com.fieldpass.student.core.model.SubmitEvidence
import com.fieldpass.student.event
import com.fieldpass.student.fix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceCaptureViewModelTest {

    private class RecordingRepo : StubRepository() {
        var createCalls = 0
        val submittedAttemptIds = mutableListOf<String>()
        var submitScript: ArrayDeque<Any> = ArrayDeque()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun createAttempt(activityId: String, action: Action): Attempt =
            attempt("att_${++createCalls}")

        val submitted = mutableListOf<SubmitEvidence>()

        override suspend fun submitAttempt(attempt: Attempt, evidence: SubmitEvidence): AttendanceEvent {
            // Copies: the ViewModel wipes the real arrays right after a successful submit.
            submitted += SubmitEvidence(evidence.selfie.copyOf(), evidence.turnPhoto.copyOf(), evidence.fix, evidence.flags)
            submittedAttemptIds += attempt.id
            gate?.await()
            return when (val step = submitScript.removeFirst()) {
                is AttendanceEvent -> step
                is AppError -> throw step
                else -> error("bad step")
            }
        }
    }

    private var clock = 0L
    private val repo = RecordingRepo()

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(location: LocationProvider) = AttendanceCaptureViewModel(
        activityId = "act_1", action = Action.CHECK_IN, repository = repo, location = location,
        integrity = { DeviceFlags(rooted = true, emulator = false) },
        elapsedRealtime = { clock }, tickerEnabled = false,
    )

    private fun AttendanceCaptureViewModel.toReady() {
        start(); onPhotosCaptured(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3))
    }

    @Test fun `submit carries both photos, the fix and the phone flags`() {
        repo.submitScript = ArrayDeque(listOf(event()))
        val vm = vm(ScriptedLocation(fix()))
        vm.start()
        vm.onPhotosCaptured(byteArrayOf(1), byteArrayOf(2))
        vm.submit()
        val evidence = repo.submitted.single()
        assertArrayEquals(byteArrayOf(1), evidence.selfie)
        assertArrayEquals(byteArrayOf(2), evidence.turnPhoto)
        assertEquals(DeviceFlags(rooted = true, emulator = false), evidence.flags)
        assertNull(vm.state.value.turnPhoto)
    }

    @Test fun `retake drops both photos`() {
        val vm = vm(ScriptedLocation(fix()))
        vm.start()
        vm.onPhotosCaptured(byteArrayOf(1), byteArrayOf(2))
        vm.retakeSelfie()
        assertNull(vm.state.value.selfie)
        assertNull(vm.state.value.turnPhoto)
    }

    @Test fun happyPath_showsServerDecision() {
        repo.submitScript = ArrayDeque(listOf(event(status = AttendanceStatus.NEEDS_REVIEW)))
        val vm = vm(ScriptedLocation(fix()))
        vm.start()
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        assertEquals(90_000L, vm.state.value.remainingMillis)
        vm.onPhotosCaptured(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3))
        assertEquals(CapturePhase.READY_TO_SUBMIT, vm.state.value.phase)
        vm.submit()
        val s = vm.state.value
        assertEquals(CapturePhase.DONE, s.phase)
        assertEquals(AttendanceStatus.NEEDS_REVIEW, s.result?.status)
        assertNull(s.selfie)
        assertEquals(listOf("att_1"), repo.submittedAttemptIds)
    }

    @Test fun startIsIdempotent_onlyOneAttemptCreated() {
        val vm = vm(ScriptedLocation(fix()))
        vm.start(); vm.start()
        assertEquals(1, repo.createCalls)
    }

    @Test fun duplicateTapWhileInFlight_isIgnored() {
        repo.gate = CompletableDeferred()
        repo.submitScript = ArrayDeque(listOf(event()))
        val vm = vm(ScriptedLocation(fix()))
        vm.toReady()
        vm.submit(); vm.submit(); vm.submit()
        assertEquals(CapturePhase.SUBMITTING, vm.state.value.phase)
        assertFalse(vm.state.value.canSubmit)
        assertEquals(1, repo.submittedAttemptIds.size)
        repo.gate!!.complete(Unit)
        assertEquals(CapturePhase.DONE, vm.state.value.phase)
    }

    @Test fun lostResponse_retriesSameAttemptId_neverCreatesNewAttempt() {
        repo.submitScript = ArrayDeque(listOf(AppError(ErrorCode.TIMEOUT), AppError(ErrorCode.NETWORK), event()))
        val vm = vm(ScriptedLocation(fix()))
        vm.toReady()
        vm.submit()
        assertEquals(CapturePhase.READY_TO_SUBMIT, vm.state.value.phase)
        assertTrue(vm.state.value.pendingReplay)
        assertEquals(ErrorCode.TIMEOUT, vm.state.value.error)
        vm.submit()
        assertEquals(ErrorCode.NETWORK, vm.state.value.error)
        vm.submit()
        assertEquals(CapturePhase.DONE, vm.state.value.phase)
        assertEquals(listOf("att_1", "att_1", "att_1"), repo.submittedAttemptIds)
        assertEquals(1, repo.createCalls)
    }

    @Test fun localExpiryDoesNotInterruptReplayOfLostResponse() {
        repo.submitScript = ArrayDeque(listOf(AppError(ErrorCode.TIMEOUT), event()))
        val vm = vm(ScriptedLocation(fix()))
        vm.toReady()
        vm.submit()
        clock = 200_000
        vm.tick()
        assertEquals(CapturePhase.READY_TO_SUBMIT, vm.state.value.phase)
        vm.submit()
        assertEquals(CapturePhase.DONE, vm.state.value.phase)
    }

    @Test fun serverAttemptExpired_offersNewAttemptViaStartOver() {
        repo.submitScript = ArrayDeque(listOf(AppError(ErrorCode.ATTEMPT_EXPIRED)))
        val vm = vm(ScriptedLocation(fix(), fix()))
        vm.toReady()
        vm.submit()
        assertEquals(CapturePhase.EXPIRED, vm.state.value.phase)
        assertNull(vm.state.value.selfie)
        vm.startOver()
        assertEquals(2, repo.createCalls)
        assertEquals("att_2", vm.state.value.attempt?.id)
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
    }

    @Test fun countdownReachingZero_movesToExpired() {
        val vm = vm(ScriptedLocation(fix()))
        vm.start()
        clock = 30_000; vm.tick()
        assertEquals(60_000L, vm.state.value.remainingMillis)
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        clock = 91_000; vm.tick()
        assertEquals(CapturePhase.EXPIRED, vm.state.value.phase)
    }

    @Test fun locationStale_refreshesLocation_sameAttempt_keepsSelfie() {
        repo.submitScript = ArrayDeque(listOf(AppError(ErrorCode.LOCATION_STALE), event()))
        val location = ScriptedLocation(fix(), fix(8.0))
        val vm = vm(location)
        vm.toReady()
        vm.submit()
        assertEquals(2, location.calls)
        assertEquals(CapturePhase.READY_TO_SUBMIT, vm.state.value.phase)
        assertEquals(8.0, vm.state.value.fix?.accuracyM)
        vm.submit()
        assertEquals(listOf("att_1", "att_1"), repo.submittedAttemptIds)
        assertEquals(CapturePhase.DONE, vm.state.value.phase)
    }

    @Test fun unusableCapture_andMultipleFaces_retakeWithSameAttempt() {
        repo.submitScript = ArrayDeque(listOf(AppError(ErrorCode.UNUSABLE_CAPTURE), AppError(ErrorCode.MULTIPLE_FACES), event()))
        val vm = vm(ScriptedLocation(fix()))
        vm.toReady()
        vm.submit()
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        assertEquals(ErrorCode.UNUSABLE_CAPTURE, vm.state.value.error)
        assertNull(vm.state.value.selfie)
        vm.onPhotosCaptured(byteArrayOf(9), byteArrayOf(9))
        vm.submit()
        assertEquals(ErrorCode.MULTIPLE_FACES, vm.state.value.error)
        vm.onPhotosCaptured(byteArrayOf(9), byteArrayOf(9))
        vm.submit()
        assertEquals(CapturePhase.DONE, vm.state.value.phase)
        assertEquals(1, repo.createCalls)
        assertEquals(listOf("att_1", "att_1", "att_1"), repo.submittedAttemptIds)
    }

    @Test fun poorAccuracy_offersRetryOrSubmitAnyway() {
        val vm = vm(ScriptedLocation(fix(250.0), fix(30.0)))
        vm.start()
        assertEquals(CapturePhase.POOR_ACCURACY, vm.state.value.phase)
        vm.retryLocation()
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        assertEquals(30.0, vm.state.value.fix?.accuracyM)
    }

    @Test fun poorAccuracy_submitAnywayProceedsToSelfie() {
        val vm = vm(ScriptedLocation(fix(250.0)))
        vm.start()
        vm.acceptPoorAccuracy()
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        assertTrue(vm.state.value.poorAccuracyAccepted)
    }

    @Test fun selfieIgnoredBeforeLocationFix() {
        val vm = vm(ScriptedLocation(fix(250.0)))
        vm.start()
        vm.onPhotosCaptured(byteArrayOf(1), byteArrayOf(1))
        assertEquals(CapturePhase.POOR_ACCURACY, vm.state.value.phase)
        assertNull(vm.state.value.selfie)
    }

    @Test fun locationFailure_isRecoverableOnSameAttempt() {
        val vm = vm(ScriptedLocation(AppError(ErrorCode.LOCATION_TIMEOUT), fix()))
        vm.start()
        assertEquals(CapturePhase.LOCATION_FAILED, vm.state.value.phase)
        assertEquals(ErrorCode.LOCATION_TIMEOUT, vm.state.value.error)
        vm.retryLocation()
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        assertEquals(1, repo.createCalls)
    }

    @Test fun attemptCreationFailure_surfacesServerCode() {
        val failing = object : StubRepository() {
            override suspend fun createAttempt(activityId: String, action: Action): Attempt =
                throw AppError(ErrorCode.ENROLLMENT_NOT_APPROVED)
        }
        val vm = AttendanceCaptureViewModel("act_1", Action.CHECK_IN, failing, ScriptedLocation(), { DeviceFlags(false, false) }, { 0L }, false)
        vm.start()
        assertEquals(CapturePhase.ATTEMPT_FAILED, vm.state.value.phase)
        assertEquals(ErrorCode.ENROLLMENT_NOT_APPROVED, vm.state.value.error)
    }

    @Test fun discard_dropsSelfieBytes() {
        val vm = vm(ScriptedLocation(fix()))
        vm.toReady()
        val bytes = vm.state.value.selfie!!.bytes
        vm.discard()
        assertTrue(bytes.all { it == 0.toByte() })
        assertNull(vm.state.value.selfie)
    }
}
