package com.fieldpass.student.data.api

import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttemptRequest
import com.fieldpass.student.core.model.AttendanceEvent
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.core.model.Attempt
import com.fieldpass.student.core.model.Enrollment
import com.fieldpass.student.core.model.HealthResponse
import com.fieldpass.student.core.model.Items
import com.fieldpass.student.core.model.LoginRequest
import com.fieldpass.student.core.model.LoginResponse
import com.fieldpass.student.core.model.User
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Url

/** Student-facing slice of the frozen contract in contracts/API.md. Paths are relative to the base URL. */
interface ApiService {
    /** Full URL so the login screen can test an address before saving it. */
    @GET
    suspend fun health(@Url url: String): HealthResponse

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("api/v1/auth/me")
    suspend fun me(): User

    @GET("api/v1/enrollment/me")
    suspend fun enrollment(): Enrollment

    @Multipart
    @POST("api/v1/enrollment")
    suspend fun submitEnrollment(@Part photo: MultipartBody.Part): Enrollment

    @GET("api/v1/activities")
    suspend fun activities(): Items<Activity>

    @GET("api/v1/activities/{id}")
    suspend fun activity(@Path("id") id: String): Activity

    @GET("api/v1/attendance/me")
    suspend fun attendance(): Items<AttendanceRecord>

    @POST("api/v1/activities/{id}/attempts")
    suspend fun createAttempt(@Path("id") activityId: String, @Body body: AttemptRequest): Attempt

    @Multipart
    @POST("api/v1/attempts/{id}/submit")
    suspend fun submitAttempt(
        @Path("id") attemptId: String,
        @Part selfie: MultipartBody.Part,
        @Part turnPhoto: MultipartBody.Part,
        @Part("lat") lat: RequestBody,
        @Part("lng") lng: RequestBody,
        @Part("accuracy_m") accuracyM: RequestBody,
        @Part("location_captured_at") capturedAt: RequestBody,
        @Part("location_is_mock") isMock: RequestBody,
        @Part("device_rooted") rooted: RequestBody,
        @Part("device_emulator") emulator: RequestBody,
        @Part("signature") signature: RequestBody,
    ): AttendanceEvent
}
