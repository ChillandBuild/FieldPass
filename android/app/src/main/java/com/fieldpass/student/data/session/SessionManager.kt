package com.fieldpass.student.data.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Signals that the stored session is no longer valid so navigation can return to login. */
class SessionManager(private val tokens: TokenStore) {
    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expired: SharedFlow<Unit> = _expired

    fun token(): String? = tokens.read()

    fun save(token: String) = tokens.write(token)

    fun clear() = tokens.clear()

    fun onSessionExpired() {
        tokens.clear()
        _expired.tryEmit(Unit)
    }
}
