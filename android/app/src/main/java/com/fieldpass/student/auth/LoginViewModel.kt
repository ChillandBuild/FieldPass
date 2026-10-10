package com.fieldpass.student.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.error.ErrorParser
import com.fieldpass.student.data.repo.FieldPassRepository
import com.fieldpass.student.data.settings.AppSettings
import com.fieldpass.student.data.settings.BaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectionResult {
    data object Ok : ConnectionResult
    data class Failed(val code: ErrorCode) : ConnectionResult
}

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val baseUrlText: String = "",
    val fixtureMode: Boolean = false,
    val isSigningIn: Boolean = false,
    val isTesting: Boolean = false,
    val error: ErrorCode? = null,
    val connection: ConnectionResult? = null,
    val signedIn: Boolean = false,
) {
    val canSignIn: Boolean get() = username.isNotBlank() && password.isNotEmpty() && !isSigningIn && !isTesting
}

class LoginViewModel(
    private val repository: FieldPassRepository,
    private val settings: AppSettings,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LoginUiState(baseUrlText = settings.baseUrl.value, fixtureMode = settings.fixtureMode.value),
    )
    val state: StateFlow<LoginUiState> = _state

    fun onUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onBaseUrl(value: String) = _state.update { it.copy(baseUrlText = value, connection = null, error = null) }

    fun onFixtureMode(enabled: Boolean) {
        settings.setFixtureMode(enabled)
        _state.update { it.copy(fixtureMode = settings.fixtureMode.value, connection = null, error = null) }
    }

    fun testConnection() {
        if (_state.value.isTesting || _state.value.isSigningIn) return
        val url = BaseUrl.normalize(_state.value.baseUrlText)
        if (url == null) {
            _state.update { it.copy(connection = ConnectionResult.Failed(ErrorCode.INVALID_BASE_URL)) }
            return
        }
        settings.setBaseUrl(url)
        _state.update { it.copy(baseUrlText = url, isTesting = true, connection = null) }
        viewModelScope.launch {
            val result = try {
                repository.testConnection(url)
                ConnectionResult.Ok
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectionResult.Failed(ErrorParser.fromThrowable(e).code)
            }
            _state.update { it.copy(isTesting = false, connection = result) }
        }
    }

    fun signIn() {
        val current = _state.value
        if (!current.canSignIn) return
        val url = BaseUrl.normalize(current.baseUrlText)
        if (url == null && !current.fixtureMode) {
            _state.update { it.copy(error = ErrorCode.INVALID_BASE_URL) }
            return
        }
        if (url != null) settings.setBaseUrl(url)
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
