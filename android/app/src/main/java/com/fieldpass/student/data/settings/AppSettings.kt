package com.fieldpass.student.data.settings

import com.fieldpass.student.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Build-configured app settings. Neither value is exposed on the student login screen. */
interface AppSettings {
    val baseUrl: StateFlow<String>
    val fixtureMode: StateFlow<Boolean>
}

class BuildConfigAppSettings : AppSettings {
    private val _baseUrl = MutableStateFlow(
        BaseUrl.normalize(BuildConfig.DEFAULT_API_BASE_URL)
            ?: BuildConfig.DEFAULT_API_BASE_URL,
    )
    override val baseUrl: StateFlow<String> = _baseUrl

    private val _fixture = MutableStateFlow(
        BuildConfig.FIXTURE_AVAILABLE && BuildConfig.FIXTURE_DEFAULT,
    )
    override val fixtureMode: StateFlow<Boolean> = _fixture
}
