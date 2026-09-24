package com.tripshare.app.data.remote

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.tripshare.app.BuildConfig
import com.tripshare.app.data.PlaceMarker
import com.tripshare.app.data.TrackingMode
import com.tripshare.app.data.TripSettings
import com.tripshare.app.data.security.CredentialStore
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class DeviceRegistrationRequest(val installationId: String)
data class DeviceRegistrationResponse(val deviceId: String, val credential: String?)
data class MarkerDto(val name: String, val lat: Double, val lon: Double)
data class CreateTripRequest(
    val origin: MarkerDto?,
    val destination: MarkerDto?,
    val sampleIntervalSec: Int,
    val uploadIntervalSec: Int,
    val mode: String,
    val maxShareSeconds: Int
)
data class SettingsRequest(
    val sampleIntervalSec: Int,
    val uploadIntervalSec: Int,
    val mode: String,
    val maxShareSeconds: Int
)
data class TripDto(
    val id: String,
    val title: String,
    val origin: MarkerDto?,
    val destination: MarkerDto?,
    val status: String,
    val startedAt: String,
    val endedAt: String?,
    val endReason: String?,
    val sampleIntervalSec: Int,
    val uploadIntervalSec: Int,
    val mode: String,
    val maxShareSeconds: Int,
    val shareUrl: String?,
    val shareExpiresAt: String?,
    val latestPositionAt: String?,
    val pointCount: Int,
    val shareRevokedAt: String?
)
data class CreateTripResponse(val trip: TripDto, val shareUrl: String?)
data class TripResponse(val trip: TripDto)
data class TripsResponse(val trips: List<TripDto>)
data class PositionInputDto(
    val id: String,
    val lat: Double,
    val lon: Double,
    val capturedAt: String,
    val accuracyM: Double,
    val speedMps: Double?,
    val speedAccuracyMps: Double?,
    val source: String?,
    val coordinateSystem: String = "WGS84"
)
data class UploadPositionsRequest(val points: List<PositionInputDto>)
data class RejectedPositionDto(val id: String, val code: String)
data class UploadPositionsResponse(
    val acceptedIds: List<String> = emptyList(),
    val duplicateIds: List<String> = emptyList(),
    val rejected: List<RejectedPositionDto> = emptyList()
)

interface TripApi {
    @POST("api/v1/devices")
    suspend fun registerDevice(@Body body: DeviceRegistrationRequest): DeviceRegistrationResponse

    @POST("api/v1/trips")
    suspend fun createTrip(@Body body: CreateTripRequest): CreateTripResponse

    @GET("api/v1/trips")
    suspend fun listTrips(): TripsResponse

    @GET("api/v1/trips/{id}")
    suspend fun getTrip(@Path("id") id: String): TripResponse

    @PATCH("api/v1/trips/{id}/settings")
    suspend fun updateSettings(@Path("id") id: String, @Body body: SettingsRequest): TripResponse

    @POST("api/v1/trips/{id}/end")
    suspend fun endTrip(@Path("id") id: String): TripResponse

    @POST("api/v1/trips/{id}/revoke")
    suspend fun revokeShare(@Path("id") id: String): TripResponse

    @DELETE("api/v1/trips/{id}")
    suspend fun deleteTrip(@Path("id") id: String)

    @POST("api/v1/trips/{id}/positions")
    suspend fun uploadPositions(@Path("id") id: String, @Body body: UploadPositionsRequest): UploadPositionsResponse
}

data class PlaceTipDto(val name: JsonElement?, val district: JsonElement?, val address: JsonElement?, val location: JsonElement?)
data class PlaceTipsResponse(val status: String?, val tips: List<PlaceTipDto>?)

interface AmapWebApi {
    @GET("v3/assistant/inputtips")
    suspend fun inputTips(
        @Query("keywords") keywords: String,
        @Query("key") key: String,
        @Query("output") output: String = "JSON"
    ): PlaceTipsResponse
}

class TripApiFactory(context: Context, private val credentialStore: CredentialStore) {
    private val appContext = context.applicationContext

    fun create(): TripApi {
        val credentialInterceptor = Interceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val isPublicRead = path.startsWith("/api/v1/public/")
            val isRegistration = path == "/api/v1/devices"
            val credential = if (!isPublicRead && !isRegistration) credentialStore.credential() else null
            val authorized = if (credential.isNullOrBlank()) request else request.newBuilder()
                .header("Authorization", "Bearer $credential").build()
            chain.proceed(authorized)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(credentialInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
        val gson = GsonBuilder().serializeNulls().create()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL.ensureTrailingSlash())
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(TripApi::class.java)
    }

    fun amapWebApi(): AmapWebApi = Retrofit.Builder()
        .baseUrl("https://restapi.amap.com/")
        .client(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(AmapWebApi::class.java)

    fun assertServerConfigured() {
        val url = BuildConfig.API_BASE_URL
        require(!url.contains("your-domain.example", ignoreCase = true)) {
            "请先设置服务器地址 TRIP_API_BASE_URL"
        }
        require(url.startsWith("https://", ignoreCase = true) || BuildConfig.DEBUG && url.startsWith("http://", true)) {
            "正式服务器地址必须使用 HTTPS"
        }
    }

    fun amapSearchConfigured() = BuildConfig.AMAP_WEB_SERVICE_KEY.isNotBlank() && BuildConfig.AMAP_WEB_SERVICE_KEY != "CHANGE_ME"

    suspend fun searchPlaces(query: String): List<com.tripshare.app.location.AmapPlace> {
        if (!amapSearchConfigured()) throw IllegalStateException("请配置高德 Web 服务 Key 后使用地点搜索")
        val response = amapWebApi().inputTips(query, BuildConfig.AMAP_WEB_SERVICE_KEY)
        if (response.status != "1") throw IllegalStateException("高德地点搜索暂不可用")
        return response.tips.orEmpty().mapNotNull { com.tripshare.app.location.AmapPlace.fromTip(it) }
    }

    private fun String.ensureTrailingSlash() = if (endsWith('/')) this else "$this/"
}

fun PlaceMarker.toDto() = MarkerDto(name, lat, lon)
fun MarkerDto.toModel() = PlaceMarker(name, lat, lon)
fun TripSettings.toRequest() = SettingsRequest(sampleIntervalSec, uploadIntervalSec, mode.wireValue, maxShareSeconds)
