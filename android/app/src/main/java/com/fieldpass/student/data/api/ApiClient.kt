package com.fieldpass.student.data.api

import com.fieldpass.student.data.session.SessionManager
import com.fieldpass.student.data.settings.AppSettings
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds the single Retrofit instance. Requests are written against a placeholder host and the
 * current base URL (editable at runtime) is swapped in per request. No logging interceptor is
 * installed, so tokens, bodies and images can never reach logcat.
 */
object ApiClient {
    const val PLACEHOLDER_HOST = "fieldpass.invalid"
    private const val PLACEHOLDER_BASE = "http://$PLACEHOLDER_HOST/"

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun create(settings: AppSettings, session: SessionManager): ApiService {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(BaseUrlInterceptor { settings.baseUrl.value })
            .addInterceptor(AuthInterceptor { session.token() })
            .build()
        return Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }
}

/** Replaces scheme/host/port of placeholder-host requests with the configured server address. */
internal class BaseUrlInterceptor(private val baseUrl: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != ApiClient.PLACEHOLDER_HOST) return chain.proceed(request)
        val target = baseUrl().toHttpUrl()
        val rewritten = request.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()
        return chain.proceed(request.newBuilder().url(rewritten).build())
    }
}

/** Adds the bearer token only to calls that target the configured server (never to login or health). */
internal class AuthInterceptor(private val token: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val isPublic = path.endsWith("/auth/login") || path.endsWith("/health")
        val bearer = token()
        if (isPublic || bearer == null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $bearer").build())
    }
}
