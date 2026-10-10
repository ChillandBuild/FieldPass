package com.fieldpass.student.enrollment

import com.fieldpass.student.StubRepository
import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.Enrollment
import com.fieldpass.student.core.model.EnrollmentStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnrollmentViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Repo(var current: Enrollment) : StubRepository() {
        var submits = 0
        var gate: CompletableDeferred<Unit>? = null
        var failure: AppError? = null
        override suspend fun enrollment() = current
        override suspend fun submitEnrollment(photo: ByteArray): Enrollment {
            submits++
            gate?.await()
            failure?.let { throw it }
            return Enrollment("enr_2", EnrollmentStatus.PENDING)
        }
    }

    private fun enrollment(status: EnrollmentStatus, reason: String? = null) =
        Enrollment(id = "enr_1", status = status, decisionReason = reason)

    @Test fun rejectedShowsReasonAndAllowsResubmissionWithoutWarning() {
        val vm = EnrollmentViewModel(Repo(enrollment(EnrollmentStatus.REJECTED, "Face not clear")))
        assertEquals("Face not clear", vm.state.value.enrollment?.decisionReason)
        vm.beginCapture()
        assertEquals(EnrollmentStep.CAMERA, vm.state.value.step)
        assertFalse(vm.state.value.confirmReplace)
    }

    @Test fun replacingApprovedPhotoRequiresConfirmation() {
        val vm = EnrollmentViewModel(Repo(enrollment(EnrollmentStatus.APPROVED)))
        vm.beginCapture()
        assertTrue(vm.state.value.confirmReplace)
        assertEquals(EnrollmentStep.STATUS, vm.state.value.step)
        vm.dismissReplace()
        assertEquals(EnrollmentStep.STATUS, vm.state.value.step)
        vm.beginCapture(); vm.confirmReplace()
        assertEquals(EnrollmentStep.CAMERA, vm.state.value.step)
    }

    @Test fun submitMovesToPendingAndDropsPhoto() {
        val repo = Repo(enrollment(EnrollmentStatus.NONE))
        val vm = EnrollmentViewModel(repo)
        vm.beginCapture(); vm.onPhoto(byteArrayOf(1, 2)); vm.submit()
        assertEquals(EnrollmentStatus.PENDING, vm.state.value.enrollment?.status)
        assertNull(vm.state.value.photo)
        assertEquals(EnrollmentStep.STATUS, vm.state.value.step)
    }

    @Test fun doubleTapUploadsOnce() {
        val repo = Repo(enrollment(EnrollmentStatus.NONE)).apply { gate = CompletableDeferred() }
        val vm = EnrollmentViewModel(repo)
        vm.beginCapture(); vm.onPhoto(byteArrayOf(1)); vm.submit(); vm.submit()
        assertEquals(1, repo.submits)
        repo.gate!!.complete(Unit)
    }

    @Test fun multipleFacesSendsBackToCameraWithGuidance() {
        val repo = Repo(enrollment(EnrollmentStatus.NONE)).apply { failure = AppError(ErrorCode.MULTIPLE_FACES) }
        val vm = EnrollmentViewModel(repo)
        vm.beginCapture(); vm.onPhoto(byteArrayOf(1)); vm.submit()
        assertEquals(EnrollmentStep.CAMERA, vm.state.value.step)
        assertEquals(ErrorCode.MULTIPLE_FACES, vm.state.value.guidance)
        assertNull(vm.state.value.photo)
    }

    @Test fun networkFailureKeepsPhotoForRetry() {
        val repo = Repo(enrollment(EnrollmentStatus.NONE)).apply { failure = AppError(ErrorCode.NETWORK) }
        val vm = EnrollmentViewModel(repo)
        vm.beginCapture(); vm.onPhoto(byteArrayOf(1)); vm.submit()
        assertEquals(EnrollmentStep.PREVIEW, vm.state.value.step)
        assertEquals(ErrorCode.NETWORK, vm.state.value.guidance)
        assertTrue(vm.state.value.photo != null)
    }
}
