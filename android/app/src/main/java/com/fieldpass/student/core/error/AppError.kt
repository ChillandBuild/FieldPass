package com.fieldpass.student.core.error

/** The single typed error carried through repositories, state machines and UI. */
class AppError(
    val code: ErrorCode,
    val serverMessage: String? = null,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(code.name, cause) {

    /** True when the request may or may not have reached the server, so the SAME attempt is retried. */
    val isLostResponse: Boolean
        get() = code == ErrorCode.NETWORK || code == ErrorCode.TIMEOUT

    val isSessionExpired: Boolean
        get() = code == ErrorCode.NOT_AUTHENTICATED

    override fun toString(): String = "AppError(${code.name}, http=$httpStatus)"
}
