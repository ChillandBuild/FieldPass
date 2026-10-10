package com.fieldpass.student

import android.content.Context
import com.fieldpass.student.capture.FusedLocationProvider
import com.fieldpass.student.capture.LocationProvider
import com.fieldpass.student.data.api.ApiClient
import com.fieldpass.student.data.device.KeystoreDeviceSigner
import com.fieldpass.student.data.repo.FakeFieldPassRepository
import com.fieldpass.student.data.repo.FieldPassRepository
import com.fieldpass.student.data.repo.RealFieldPassRepository
import com.fieldpass.student.data.repo.SwitchableRepository
import com.fieldpass.student.data.session.KeystoreTokenStore
import com.fieldpass.student.data.session.SessionManager
import com.fieldpass.student.data.settings.AppSettings
import com.fieldpass.student.data.settings.BuildConfigAppSettings

/** Manual dependency injection: one place that wires the app's long-lived objects. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settings: AppSettings = BuildConfigAppSettings()
    val session = SessionManager(KeystoreTokenStore(appContext))
    val repository: FieldPassRepository = SwitchableRepository(
        settings = settings,
        real = RealFieldPassRepository(ApiClient.create(settings, session), session, KeystoreDeviceSigner()),
        fake = FakeFieldPassRepository(),
    )
    val location: LocationProvider = FusedLocationProvider(appContext)
}
