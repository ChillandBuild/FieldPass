package com.fieldpass.student.data.repo

import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Attempt
import com.fieldpass.student.core.model.SubmitEvidence
import com.fieldpass.student.data.settings.AppSettings

/** Delegates to the fake repository when fixture mode is on, otherwise to the real one. */
class SwitchableRepository(
    private val settings: AppSettings,
    private val real: FieldPassRepository,
    private val fake: FieldPassRepository,
) : FieldPassRepository {
    private val active: FieldPassRepository get() = if (settings.fixtureMode.value) fake else real

    override suspend fun testConnection(baseUrl: String) = active.testConnection(baseUrl)
    override suspend fun login(username: String, password: String) = active.login(username, password)
    override suspend fun logout() { real.logout(); fake.logout() }
    override suspend fun currentUser() = active.currentUser()
    override suspend fun enrollment() = active.enrollment()
    override suspend fun submitEnrollment(photo: ByteArray) = active.submitEnrollment(photo)
    override suspend fun activities() = active.activities()
    override suspend fun activity(id: String) = active.activity(id)
    override suspend fun attendanceHistory() = active.attendanceHistory()
    override suspend fun createAttempt(activityId: String, action: Action) = active.createAttempt(activityId, action)
    override suspend fun submitAttempt(attempt: Attempt, evidence: SubmitEvidence) = active.submitAttempt(attempt, evidence)
    override suspend fun submitPresenceCode(attemptId: String, code: String) = active.submitPresenceCode(attemptId, code)
}
