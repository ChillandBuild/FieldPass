package com.fieldpass.student.data.session

/** Storage for the bearer token. Implementations must keep it encrypted at rest. */
interface TokenStore {
    fun read(): String?
    fun write(token: String)
    fun clear()
}

/** In-memory store for tests and fixture mode. Not persisted. */
class MemoryTokenStore : TokenStore {
    @Volatile private var token: String? = null
    override fun read(): String? = token
    override fun write(token: String) { this.token = token }
    override fun clear() { token = null }
}
