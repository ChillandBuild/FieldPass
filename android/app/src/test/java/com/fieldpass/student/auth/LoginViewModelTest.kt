package com.fieldpass.student.auth

import com.fieldpass.student.FakeSettings
import com.fieldpass.student.StubRepository
import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.Role
import com.fieldpass.student.core.model.User
import kotlinx.coroutines.CompletableDeferred
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
class LoginViewModelTest {
    private val settings = FakeSettings()

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Repo(val onLogin: suspend () -> User = { User("u_s1", "student1", "Asha", Role.STUDENT) }) : StubRepository() {
        var logins = 0
        var tested: String? = null
        var testFailure: AppError? = null
        override suspend fun login(username: String, password: String): User { logins++; return onLogin() }
        override suspend fun testConnection(baseUrl: String) { tested = baseUrl; testFailure?.let { throw it } }
    }

    @Test fun facultyAccountGetsClearMessage() {
        val vm = LoginViewModel(Repo({ throw AppError(ErrorCode.FACULTY_ACCOUNT) }), settings)
        vm.onUsername("faculty1"); vm.onPassword("x")
        vm.signIn()
        assertEquals(ErrorCode.FACULTY_ACCOUNT, vm.state.value.error)
        assertFalse(vm.state.value.signedIn)
    }

    @Test fun successfulLoginSignalsAndClearsPassword() {
        val vm = LoginViewModel(Repo(), settings)
        vm.onUsername("student1"); vm.onPassword("pw")
        vm.signIn()
        assertTrue(vm.state.value.signedIn)
        assertEquals("", vm.state.value.password)
    }

    @Test fun duplicateTapsSendOneLoginRequest() {
        val gate = CompletableDeferred<Unit>()
        val repo = Repo({ gate.await(); User("u", "s", "A", Role.STUDENT) })
        val vm = LoginViewModel(repo, settings)
        vm.onUsername("student1"); vm.onPassword("pw")
        vm.signIn(); vm.signIn()
        assertEquals(1, repo.logins)
        gate.complete(Unit)
        assertTrue(vm.state.value.signedIn)
    }

    @Test fun signInNeedsUsernameAndPassword() {
        val repo = Repo()
        val vm = LoginViewModel(repo, settings)
        vm.signIn()
        assertEquals(0, repo.logins)
    }

    @Test fun testConnectionNormalisesPersistsAndReportsOk() {
        val repo = Repo()
        val vm = LoginViewModel(repo, settings)
        vm.onBaseUrl("10.0.0.5:8000/")
        vm.testConnection()
        assertEquals("http://10.0.0.5:8000", repo.tested)
        assertEquals("http://10.0.0.5:8000", settings.baseUrl.value)
        assertEquals(ConnectionResult.Ok, vm.state.value.connection)
    }

    @Test fun testConnectionReportsUnreachableAndBadAddress() {
        val repo = Repo().apply { testFailure = AppError(ErrorCode.NETWORK) }
        val vm = LoginViewModel(repo, settings)
        vm.onBaseUrl("10.0.0.5:8000")
        vm.testConnection()
        assertEquals(ConnectionResult.Failed(ErrorCode.NETWORK), vm.state.value.connection)
        vm.onBaseUrl("not a url ::")
        vm.testConnection()
        assertEquals(ConnectionResult.Failed(ErrorCode.INVALID_BASE_URL), vm.state.value.connection)
    }
}
