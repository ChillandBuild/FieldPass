package com.fieldpass.student.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.error.ErrorParser
import com.fieldpass.student.data.repo.FieldPassRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isSigningIn: Boolean = false,
    val error: ErrorCode? = null,
    val signedIn: Boolean = false,
) {
    val canSignIn: Boolean get() = username.isNotBlank() && password.isNotEmpty() && !isSigningIn
}

class LoginViewModel(private val repository: FieldPassRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    fun onUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun signIn() {
        val current = _state.value
        if (!current.canSignIn) return
        _state.update { it.copy(isSigningIn = true, error = null) }
        viewModelScope.launch {
            try {
                repository.login(current.username, current.password)
                _state.update { it.copy(isSigningIn = false, password = "", signedIn = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = (e as? AppError)?.code ?: ErrorParser.fromThrowable(e).code
                _state.update { it.copy(isSigningIn = false, error = code) }
            }
        }
    }

    fun onSignInHandled() = _state.update { it.copy(signedIn = false) }
}
