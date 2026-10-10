package com.fieldpass.student.activities

import com.fieldpass.student.StubRepository
import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.core.model.Enrollment
import com.fieldpass.student.core.model.EnrollmentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityDetailViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val activity = Activity(
        id = "act_1", name = "Visit", venueName = "Gate", venueLat = 12.9, venueLng = 77.5, radiusM = 100,
        startsAt = "2026-10-10T09:30:00Z", endsAt = "2026-10-10T13:30:00Z",
        myAttendance = AttendanceRecord(activityId = "act_1", activityName = "Visit", nextActions = listOf(Action.CHECK_IN)),
    )

    private class Repo(val activity: Activity, val enrollment: () -> Enrollment) : StubRepository() {
        override suspend fun activity(id: String) = activity
        override suspend fun enrollment() = enrollment.invoke()
    }

    @Test fun unapprovedStudentIsSentToEnrollmentInsteadOfOfferedCheckIn() {
        for (status in listOf(EnrollmentStatus.NONE, EnrollmentStatus.PENDING, EnrollmentStatus.REJECTED)) {
            val vm = ActivityDetailViewModel("act_1", Repo(activity) { Enrollment(status = status) })
            assertTrue("$status", vm.state.value.needsEnrollment)
        }
    }

    @Test fun approvedStudentSeesActions() {
        val vm = ActivityDetailViewModel("act_1", Repo(activity) { Enrollment(status = EnrollmentStatus.APPROVED) })
        assertFalse(vm.state.value.needsEnrollment)
        assertEquals(listOf(Action.CHECK_IN), ActivityActions.visible(requireNotNull(vm.state.value.activity)))
    }

    @Test fun enrollmentLookupFailureDoesNotHideActionsBecauseServerStillEnforcesTheGate() {
        val vm = ActivityDetailViewModel("act_1", Repo(activity) { throw AppError(ErrorCode.NETWORK) })
        assertFalse(vm.state.value.needsEnrollment)
        assertEquals(activity, vm.state.value.activity)
    }
}
