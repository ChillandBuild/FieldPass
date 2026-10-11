package com.fieldpass.student.data

import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.DeviceFlags
import com.fieldpass.student.core.model.SubmitEvidence
import com.fieldpass.student.data.repo.FakeFieldPassRepository
import com.fieldpass.student.fix
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** Fixture mode treats any fp1. code as verified; this pins that and the flag on the resulting event. */
class FakeRepositoryPresenceTest {
    private var now = Instant.parse("2026-10-10T09:31:00Z")
    private val repo = FakeFieldPassRepository(now = { now }, latencyMs = 0)
    private val evidence = SubmitEvidence(byteArrayOf(1), byteArrayOf(2), fix(), DeviceFlags(false, false))

    @Test fun anyFp1CodeIsVerifiedAndOtherStringsAreNot() = runTest {
        val attempt = repo.createAttempt("act_1", Action.CHECK_IN)
        assertTrue(repo.submitPresenceCode(attempt.id, "fp1.1.anything"))
        assertFalse(repo.submitPresenceCode(attempt.id, "hello"))
    }

    @Test fun theEventIsMarkedPresenceVerifiedAfterAGoodCode() = runTest {
        val attempt = repo.createAttempt("act_1", Action.CHECK_IN)
        repo.submitPresenceCode(attempt.id, "fp1.1.a")
        assertTrue(repo.submitAttempt(attempt, evidence).presenceVerified)
    }

    @Test fun theEventIsNotMarkedWithoutAGoodCode() = runTest {
        val attempt = repo.createAttempt("act_1", Action.CHECK_IN)
        repo.submitPresenceCode(attempt.id, "not a code")
        assertFalse(repo.submitAttempt(attempt, evidence).presenceVerified)
    }

    @Test fun unknownAttemptIsNotFoundAndAnOldAttemptIsExpired() = runTest {
        try { repo.submitPresenceCode("att_nope", "fp1.1.a"); fail() } catch (e: AppError) { assertEquals(ErrorCode.NOT_FOUND, e.code) }
        val attempt = repo.createAttempt("act_1", Action.CHECK_IN)
        now = now.plusSeconds(120)
        try { repo.submitPresenceCode(attempt.id, "fp1.1.a"); fail() } catch (e: AppError) { assertEquals(ErrorCode.ATTEMPT_EXPIRED, e.code) }
    }
}
