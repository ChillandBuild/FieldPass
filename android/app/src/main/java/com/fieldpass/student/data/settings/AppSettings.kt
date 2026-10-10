package com.fieldpass.student.data.settings

import android.content.Context
import androidx.core.content.edit
import com.fieldpass.student.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Persisted, non-secret settings: server address and (debug builds only) fixture mode. */
interface AppSettings {
    val baseUrl: StateFlow<String>
    val fixtureMode: StateFlow<Boolean>
    fun setBaseUrl(normalized: String)
    fun setFixtureMode(enabled: Boolean)
}

class SharedPrefsAppSettings(context: Context) : AppSettings {
    private val prefs = context.getSharedPreferences("fieldpass_settings", Context.MODE_PRIVATE)

    private val _baseUrl = MutableStateFlow(
        prefs.getString(KEY_BASE_URL, null)?.let(BaseUrl::normalize)
            ?: BaseUrl.normalize(BuildConfig.DEFAULT_API_BASE_URL)
            ?: BuildConfig.DEFAULT_API_BASE_URL,
    )
    override val baseUrl: StateFlow<String> = _baseUrl

    private val _fixture = MutableStateFlow(
        BuildConfig.FIXTURE_AVAILABLE && prefs.getBoolean(KEY_FIXTURE, BuildConfig.FIXTURE_DEFAULT),
    )
    override val fixtureMode: StateFlow<Boolean> = _fixture

    override fun setBaseUrl(normalized: String) {
        prefs.edit { putString(KEY_BASE_URL, normalized) }
        _baseUrl.value = normalized
    }

    override fun setFixtureMode(enabled: Boolean) {
        val effective = enabled && BuildConfig.FIXTURE_AVAILABLE
        prefs.edit { putBoolean(KEY_FIXTURE, effective) }
        _fixture.value = effective
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_FIXTURE = "fixture_mode"
    }
}
