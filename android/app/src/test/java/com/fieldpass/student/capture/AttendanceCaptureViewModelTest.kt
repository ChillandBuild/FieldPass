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
        val presenceCalls = mutableListOf<Pair<String, String>>()
        var presenceScript: ArrayDeque<Any> = ArrayDeque()
        var presenceGate: CompletableDeferred<Unit>? = null

        override suspend fun submitPresenceCode(attemptId: String, code: String): Boolean {
            presenceCalls += attemptId to code
            presenceGate?.await()
            return when (val step = presenceScript.removeFirst()) {
                is Boolean -> step
                is AppError -> throw step
                else -> error("bad step")
            }
        }

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

    /** Most tests are about the selfie and submit steps, so the code step is off unless a test asks for it. */
    private fun vm(location: LocationProvider, hasBackCamera: Boolean = false) = AttendanceCaptureViewModel(
        activityId = "act_1", action = Action.CHECK_IN, repository = repo, location = location,
        integrity = { DeviceFlags(rooted = true, emulator = false) },
        elapsedRealtime = { clock }, tickerEnabled = false, hasBackCamera = hasBackCamera,
    )

    private fun codeVm(location: LocationProvider = ScriptedLocation(fix())) = vm(location, hasBackCamera = true)

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

    // ---- rotating presence code step ----

    @Test fun codeStep_scanThenSelfie_phaseOrder() {
        repo.presenceScript = ArrayDeque(listOf(true))
        repo.submitScript = ArrayDeque(listOf(event()))
        val vm = codeVm()
        vm.start()
        assertEquals(CapturePhase.AWAITING_CODE, vm.state.value.phase)
        assertEquals(90_000L, vm.state.value.remainingMillis) // the one attempt countdown keeps running
        assertFalse(vm.state.value.codeVerified)
        vm.onCodeScanned("fp1.176000000.AbCdEf")
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        assertTrue(vm.state.value.codeVerified)
        assertEquals(listOf("att_1" to "fp1.176000000.AbCdEf"), repo.presenceCalls)
        vm.onPhotosCaptured(byteArrayOf(1), byteArrayOf(2))
        assertEquals(CapturePhase.READY_TO_SUBMIT, vm.state.value.phase)
        vm.submit()
        assertEquals(CapturePhase.DONE, vm.state.value.phase)
        assertEquals(1, repo.createCalls)
    }

    @Test fun codeStep_skipGoesToSelfieWithoutAnyCall() {
        val vm = codeVm()
        vm.start()
        vm.skipCode()
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        assertFalse(vm.state.value.codeVerified)
        assertTrue(repo.presenceCalls.isEmpty())
    }

    @Test fun codeStep_photosAreIgnoredUntilTheCodeStepIsDone() {
        val vm = codeVm()
        vm.start()
        vm.onPhotosCaptured(byteArrayOf(1), byteArrayOf(2))
        assertEquals(CapturePhase.AWAITING_CODE, vm.state.value.phase)
        assertNull(vm.state.value.selfie)
    }

    @Test fun codeStep_tooOldCodeKeepsScanningThenAGoodCodeContinues() {
        repo.presenceScript = ArrayDeque(listOf(false, true))
        val vm = codeVm()
        vm.start()
        vm.onCodeScanned("fp1.1.old")
        assertEquals(CapturePhase.AWAITING_CODE, vm.state.value.phase)
        assertEquals(CodeStatus.TOO_OLD, vm.state.value.codeStatus)
        assertFalse(vm.state.value.codeVerified)
        vm.onCodeScanned("fp1.2.fresh")
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        assertTrue(vm.state.value.codeVerified)
        assertEquals(listOf("fp1.1.old", "fp1.2.fresh"), repo.presenceCalls.map { it.second })
    }

    @Test fun codeStep_theSameStringIsNeverSentTwice() {
        repo.presenceScript = ArrayDeque(listOf(false, false))
        val vm = codeVm()
        vm.start()
        repeat(30) { vm.onCodeScanned("fp1.1.same") } // the analyzer fires many times per second
        assertEquals(1, repo.presenceCalls.size)
        assertEquals(CodeStatus.TOO_OLD, vm.state.value.codeStatus)
        vm.onCodeScanned("fp1.1.same") // still on screen after the answer came back: not sent again
        assertEquals(1, repo.presenceCalls.size)
        vm.onCodeScanned("fp1.2.next")
        assertEquals(2, repo.presenceCalls.size)
    }

    @Test fun codeStep_neverTwoCallsAtOnce_andADroppedScanCanBeScannedAgain() {
        repo.presenceGate = CompletableDeferred()
        repo.presenceScript = ArrayDeque(listOf(false, false))
        val vm = codeVm()
        vm.start()
        vm.onCodeScanned("fp1.1.a")
        assertEquals(CodeStatus.CHECKING, vm.state.value.codeStatus)
        vm.onCodeScanned("fp1.2.b") // dropped: a call is running
        assertEquals(1, repo.presenceCalls.size)
        repo.presenceGate!!.complete(Unit)
        assertEquals(CodeStatus.TOO_OLD, vm.state.value.codeStatus)
        vm.onCodeScanned("fp1.2.b") // was never sent, so it is accepted now
        assertEquals(listOf("fp1.1.a", "fp1.2.b"), repo.presenceCalls.map { it.second })
    }

    @Test fun codeStep_stringsWithoutTheFp1PrefixAreIgnored() {
        val vm = codeVm()
        vm.start()
        listOf("", "https://example.com", "FP1.1.abc", "fp2.1.abc", " fp1.1.abc", "x".repeat(300), "fp1." + "x".repeat(200))
            .forEach { vm.onCodeScanned(it) }
        assertTrue(repo.presenceCalls.isEmpty())
        assertEquals(CapturePhase.AWAITING_CODE, vm.state.value.phase)
        assertEquals(CodeStatus.SCANNING, vm.state.value.codeStatus)
    }

    @Test fun codeStep_sameCodeIsSentAgainAfterANetworkError() {
        repo.presenceScript = ArrayDeque(listOf(AppError(ErrorCode.NETWORK), true))
        val vm = codeVm()
        vm.start()
        vm.onCodeScanned("fp1.1.a")
        assertEquals(CodeStatus.FAILED, vm.state.value.codeStatus)
        vm.onCodeScanned("fp1.1.a") // still on the faculty screen: the failed send never reached the server
        assertEquals(listOf("fp1.1.a", "fp1.1.a"), repo.presenceCalls.map { it.second })
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        assertTrue(vm.state.value.codeVerified)
    }

    @Test fun codeStep_networkErrorKeepsThePhaseAllowsSkipAndTheNextCodeRetries() {
        repo.presenceScript = ArrayDeque(listOf(AppError(ErrorCode.NETWORK), AppError(ErrorCode.TIMEOUT)))
        val vm = codeVm()
        vm.start()
        vm.onCodeScanned("fp1.1.a")
        assertEquals(CapturePhase.AWAITING_CODE, vm.state.value.phase)
        assertEquals(CodeStatus.FAILED, vm.state.value.codeStatus)
        assertEquals(ErrorCode.NETWORK, vm.state.value.error)
        vm.onCodeScanned("fp1.2.b")
        assertEquals(ErrorCode.TIMEOUT, vm.state.value.error)
        assertEquals(2, repo.presenceCalls.size)
        vm.skipCode()
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.codeVerified)
    }

    @Test fun codeStep_otherServerErrorsNeverBlockTheStudent() {
        repo.presenceScript = ArrayDeque(listOf(AppError(ErrorCode.NOT_FOUND), AppError(ErrorCode.FORBIDDEN)))
        val vm = codeVm()
        vm.start()
        vm.onCodeScanned("fp1.1.a")
        assertEquals(CapturePhase.AWAITING_CODE, vm.state.value.phase)
        assertEquals(ErrorCode.NOT_FOUND, vm.state.value.error)
        vm.onCodeScanned("fp1.2.b")
        assertEquals(CapturePhase.AWAITING_CODE, vm.state.value.phase)
        assertEquals(ErrorCode.FORBIDDEN, vm.state.value.error)
        vm.skipCode()
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
    }

    @Test fun codeStep_attemptExpiredGoesToExpired() {
        repo.presenceScript = ArrayDeque(listOf(AppError(ErrorCode.ATTEMPT_EXPIRED)))
        val vm = codeVm()
        vm.start()
        vm.onCodeScanned("fp1.1.a")
        assertEquals(CapturePhase.EXPIRED, vm.state.value.phase)
        assertNull(vm.state.value.error)
    }

    @Test fun codeStep_localCountdownStillExpiresTheAttemptAndDropsALateAnswer() {
        repo.presenceGate = CompletableDeferred()
        repo.presenceScript = ArrayDeque(listOf(true))
        val vm = codeVm()
        vm.start()
        vm.onCodeScanned("fp1.1.a")
        clock = 91_000; vm.tick()
        assertEquals(CapturePhase.EXPIRED, vm.state.value.phase)
        repo.presenceGate!!.complete(Unit)
        assertEquals(CapturePhase.EXPIRED, vm.state.value.phase)
        assertFalse(vm.state.value.codeVerified)
    }

    @Test fun codeStep_skipWhileACallIsRunningIgnoresItsAnswer() {
        repo.presenceGate = CompletableDeferred()
        repo.presenceScript = ArrayDeque(listOf(true))
        val vm = codeVm()
        vm.start()
        vm.onCodeScanned("fp1.1.a")
        vm.skipCode()
        repo.presenceGate!!.complete(Unit)
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        assertFalse(vm.state.value.codeVerified)
        vm.onCodeScanned("fp1.2.b") // no longer in the code step
        assertEquals(1, repo.presenceCalls.size)
    }

    @Test fun codeStep_scansBeforeALocationFixAreIgnored() {
        val vm = codeVm(ScriptedLocation(fix(250.0)))
        vm.start()
        assertEquals(CapturePhase.POOR_ACCURACY, vm.state.value.phase)
        vm.onCodeScanned("fp1.1.a")
        assertTrue(repo.presenceCalls.isEmpty())
    }

    @Test fun codeStep_poorAccuracyContinueAnywayStillPassesThroughTheCodeStep() {
        val vm = codeVm(ScriptedLocation(fix(250.0)))
        vm.start()
        vm.acceptPoorAccuracy()
        assertEquals(CapturePhase.AWAITING_CODE, vm.state.value.phase)
        assertTrue(vm.state.value.poorAccuracyAccepted)
    }

    @Test fun codeStep_betterFixAfterPoorAccuracyAlsoGoesToTheCodeStep() {
        val vm = codeVm(ScriptedLocation(fix(250.0), fix(30.0)))
        vm.start()
        vm.retryLocation()
        assertEquals(CapturePhase.AWAITING_CODE, vm.state.value.phase)
    }

    @Test fun codeStep_noBackCameraSkipsTheStepAutomatically() {
        val vm = vm(ScriptedLocation(fix()), hasBackCamera = false)
        vm.start()
        assertEquals(CapturePhase.AWAITING_SELFIE, vm.state.value.phase)
        vm.onCodeScanned("fp1.1.a")
        assertTrue(repo.presenceCalls.isEmpty())
    }

    @Test fun codeStep_isNotRepeatedWhenTheLocationIsRefreshedAfterAStaleSubmit() {
        repo.presenceScript = ArrayDeque(listOf(true))
        repo.submitScript = ArrayDeque(listOf(AppError(ErrorCode.LOCATION_STALE), event()))
        val vm = codeVm(ScriptedLocation(fix(), fix(8.0)))
        vm.start()
        vm.onCodeScanned("fp1.1.a")
        vm.onPhotosCaptured(byteArrayOf(1), byteArrayOf(2))
        vm.submit()
        assertEquals(CapturePhase.READY_TO_SUBMIT, vm.state.value.phase)
        assertTrue(vm.state.value.codeVerified)
        assertEquals(1, repo.presenceCalls.size)
    }

    @Test fun codeStep_startOverBeginsANewCodeStepAndForgetsOldCodes() {
        repo.presenceScript = ArrayDeque(listOf(false, true))
        val vm = codeVm(ScriptedLocation(fix(), fix()))
        vm.start()
        vm.onCodeScanned("fp1.1.a")
        clock = 91_000; vm.tick()
        assertEquals(CapturePhase.EXPIRED, vm.state.value.phase)
        clock = 0
        vm.startOver()
        assertEquals(CapturePhase.AWAITING_CODE, vm.state.value.phase)
        assertEquals(CodeStatus.SCANNING, vm.state.value.codeStatus)
        vm.onCodeScanned("fp1.1.a") // a fresh attempt: the same string is a new question
        assertEquals(listOf("att_1" to "fp1.1.a", "att_2" to "fp1.1.a"), repo.presenceCalls)
        assertTrue(vm.state.value.codeVerified)
    }
}
