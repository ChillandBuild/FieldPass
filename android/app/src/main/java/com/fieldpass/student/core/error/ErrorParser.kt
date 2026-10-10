package com.fieldpass.student.core.error

import com.fieldpass.student.core.model.ApiErrorEnvelope
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.SocketTimeoutException

/** Turns HTTP error bodies and thrown exceptions into [AppError]. Never logs bodies or tokens. */
object ErrorParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun fromHttp(status: Int, body: String?): AppError {
        val envelope = body?.takeIf { it.isNotBlank() }?.let {
            try {
                json.decodeFromString<ApiErrorEnvelope>(it)
            } catch (_: Exception) {
                null
            }
        }
        if (envelope == null) {
            val code = if (status == 401) ErrorCode.NOT_AUTHENTICATED else ErrorCode.SERVER_ERROR
            return AppError(code, httpStatus = status)
        }
        return AppError(ErrorCode.fromWire(envelope.error.code), envelope.error.message, status)
    }

    fun fromThrowable(t: Throwable): AppError = when (t) {
        is AppError -> t
        is SocketTimeoutException -> AppError(ErrorCode.TIMEOUT, cause = t)
        is IOException -> if (t.message?.contains("timeout", ignoreCase = true) == true) {
            AppError(ErrorCode.TIMEOUT, cause = t)
        } else {
            AppError(ErrorCode.NETWORK, cause = t)
        }
        is IllegalArgumentException -> AppError(ErrorCode.INVALID_BASE_URL, cause = t)
        else -> AppError(ErrorCode.UNKNOWN, cause = t)
    }
}
