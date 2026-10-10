package com.fieldpass.student.auth

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
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Repo(val onLogin: suspend () -> User = { User("u_s1", "student1", "Asha", Role.STUDENT) }) : StubRepository() {
        var logins = 0
        override suspend fun login(username: String, password: String): User { logins++; return onLogin() }
    }

    @Test fun facultyAccountGetsClearMessage() {
        val vm = LoginViewModel(Repo({ throw AppError(ErrorCode.FACULTY_ACCOUNT) }))
        vm.onUsername("faculty1"); vm.onPassword("x")
        vm.signIn()
        assertEquals(ErrorCode.FACULTY_ACCOUNT, vm.state.value.error)
        assertFalse(vm.state.value.signedIn)
    }

    @Test fun successfulLoginSignalsAndClearsPassword() {
        val vm = LoginViewModel(Repo())
        vm.onUsername("student1"); vm.onPassword("pw")
        vm.signIn()
        assertTrue(vm.state.value.signedIn)
        assertEquals("", vm.state.value.password)
    }

    @Test fun duplicateTapsSendOneLoginRequest() {
        val gate = CompletableDeferred<Unit>()
        val repo = Repo({ gate.await(); User("u", "s", "A", Role.STUDENT) })
        val vm = LoginViewModel(repo)
        vm.onUsername("student1"); vm.onPassword("pw")
        vm.signIn(); vm.signIn()
        assertEquals(1, repo.logins)
        gate.complete(Unit)
        assertTrue(vm.state.value.signedIn)
    }

    @Test fun signInNeedsUsernameAndPassword() {
        val repo = Repo()
        val vm = LoginViewModel(repo)
        vm.signIn()
        assertEquals(0, repo.logins)
    }
}
