package com.tripshare.app.data.remote

import android.content.Context
import com.google.gson.JsonParser
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.tripshare.app.BuildConfig
import com.tripshare.app.data.PlaceMarker
import com.tripshare.app.data.TrackingMode
import com.tripshare.app.data.TripSettings
import com.tripshare.app.data.security.CredentialStore
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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
    val maxShareSeconds: Int,
    val mapProvider: String = "OSM"
)
data class SettingsRequest(
    val sampleIntervalSec: Int,
    val uploadIntervalSec: Int,
    val mode: String,
    val maxShareSeconds: Int
)
data class DestinationRequest(val destination: MarkerDto?)
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
    val mapProvider: String? = "OSM",
    val shareUrl: String?,
    val shareExpiresAt: String?,
    val latestPositionAt: String?,
    val latestPositionLabel: String? = null,
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

    @PATCH("api/v1/trips/{id}/destination")
    suspend fun updateDestination(@Path("id") id: String, @Body body: DestinationRequest): TripResponse

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
    private val serverAddressStore = ServerAddressStore(appContext)

    fun create(): TripApi {
        val baseUrl = currentServerAddress()
            ?: throw IllegalStateException("请先在主页更多菜单中设置服务器地址")
        val credentialInterceptor = Interceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val isPublicRead = path.startsWith("/api/v1/public/")
            val isRegistration = path == "/api/v1/devices"
            val configuredOrigin = baseUrl.toHttpUrlOrNull()
            val requestIsConfiguredOrigin = configuredOrigin != null &&
                request.url.scheme == configuredOrigin.scheme &&
                request.url.host == configuredOrigin.host &&
                request.url.port == configuredOrigin.port
            val credential = if (requestIsConfiguredOrigin && !isPublicRead && !isRegistration) {
                credentialStore.credential()
            } else null
            val authorized = if (credential.isNullOrBlank()) request else request.newBuilder()
                .header("Authorization", "Bearer $credential").build()
            chain.proceed(authorized)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(credentialInterceptor)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
        val gson = GsonBuilder().serializeNulls().create()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(TripApi::class.java)
    }

    fun currentServerAddress(): String? = serverAddressStore.currentBaseUrl()

    fun isSafeShareUrl(shareUrl: String): Boolean {
        val share = shareUrl.toHttpUrlOrNull() ?: return false
        if (share.username.isNotEmpty() || share.password.isNotEmpty()) return false
        return share.scheme == "https" ||
            (share.scheme == "http" && ServerAddressValidator.allowsCleartextHost(share.host))
    }

    fun saveServerAddress(baseUrl: String) = serverAddressStore.save(baseUrl)

    suspend fun verifyServerAddress(baseUrl: String) = withContext(Dispatchers.IO) {
        val normalized = ServerAddressValidator.normalize(baseUrl)
        val url = normalized.toHttpUrlOrNull()!!.resolve("healthz")
            ?: throw IllegalArgumentException("服务器地址无效")
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder().url(url).get().build()
        val response = try {
            client.newCall(request).execute()
        } catch (failure: IOException) {
            throw IOException("暂时无法连接服务器，请检查地址、端口和网络。", failure)
        }
        response.use {
            if (response.code != 200) {
                throw IOException("服务器健康检查失败（HTTP ${response.code}），请检查地址和网络")
            }
            val content = try {
                response.body?.string() ?: throw IOException("服务器健康检查没有返回内容")
            } catch (failure: IOException) {
                throw IOException("读取服务器响应失败，请检查网络后重试。", failure)
            }
            val json = runCatching { JsonParser.parseString(content) }.getOrNull()
            val status = json?.takeIf { it.isJsonObject }?.asJsonObject?.get("status")
            if (status == null || !status.isJsonPrimitive || !status.asJsonPrimitive.isString || status.asString != "ok") {
                throw IOException("服务器健康检查响应无效，预期 status 为 ok")
            }
        }
    }

    fun amapWebApi(): AmapWebApi = Retrofit.Builder()
        .baseUrl("https://restapi.amap.com/")
        .client(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(AmapWebApi::class.java)

    fun assertServerConfigured() {
        check(currentServerAddress() != null) { "请先在主页更多菜单中设置服务器地址" }
    }

    fun amapSearchConfigured() = resolveAmapWebServiceKey() != null

    private fun resolveAmapWebServiceKey(): String? {
        val stored = com.tripshare.app.location.MapConfigStore(appContext).amapWebServiceKey()
        if (!stored.isNullOrBlank()) return stored
        return BuildConfig.AMAP_WEB_SERVICE_KEY.takeIf { it.isNotBlank() && it != "CHANGE_ME" }
    }

    suspend fun searchPlaces(query: String): List<com.tripshare.app.location.AmapPlace> {
        val key = resolveAmapWebServiceKey() ?: throw IllegalStateException("请先在地图服务设置中配置高德 Web 服务 Key")
        val response = amapWebApi().inputTips(query.trim(), key)
        if (response.status != "1") throw IllegalStateException("高德地点搜索暂不可用")
        return response.tips.orEmpty().mapNotNull { com.tripshare.app.location.AmapPlace.fromTip(it) }
    }
}

fun PlaceMarker.toDto() = MarkerDto(name, lat, lon)
fun MarkerDto.toModel() = PlaceMarker(name, lat, lon)
fun TripSettings.toRequest() = SettingsRequest(sampleIntervalSec, uploadIntervalSec, mode.wireValue, maxShareSeconds)
