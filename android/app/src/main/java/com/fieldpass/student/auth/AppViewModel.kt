package com.fieldpass.student.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.error.ErrorParser
import com.fieldpass.student.data.repo.FieldPassRepository
import com.fieldpass.student.data.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Checking : SessionState
    data class SignedOut(val sessionExpired: Boolean = false) : SessionState
    data object SignedIn : SessionState
    data class CannotReachServer(val code: ErrorCode) : SessionState
}

/** App-wide session state: restores a saved session at launch and reacts to expiry anywhere. */
class AppViewModel(
    private val repository: FieldPassRepository,
    session: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow<SessionState>(SessionState.Checking)
    val state: StateFlow<SessionState> = _state

    init {
        viewModelScope.launch { session.expired.collect { _state.value = SessionState.SignedOut(sessionExpired = true) } }
        restore()
    }

    fun restore() {
        _state.value = SessionState.Checking
        viewModelScope.launch {
            try {
                val user = repository.currentUser()
                _state.value = if (user != null) SessionState.SignedIn else SessionState.SignedOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SessionState.CannotReachServer(ErrorParser.fromThrowable(e).code)
            }
        }
    }

    fun onSignedIn() { _state.value = SessionState.SignedIn }

    fun signOut() {
        viewModelScope.launch {
            repository.logout()
            _state.value = SessionState.SignedOut()
        }
    }
}
