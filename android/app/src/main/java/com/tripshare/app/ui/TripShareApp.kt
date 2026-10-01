package com.tripshare.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tripshare.app.data.CapturedPosition
import com.tripshare.app.data.LocalTripSummary
import com.tripshare.app.data.MapProvider
import com.tripshare.app.data.PlaceMarker
import com.tripshare.app.data.TrackingMode
import com.tripshare.app.data.TripRepository
import com.tripshare.app.data.TripSettings
import com.tripshare.app.data.local.PositionEntity
import com.tripshare.app.location.AmapConsentStore
import com.tripshare.app.location.AmapLocationSampler
import com.tripshare.app.location.AmapPlace
import com.tripshare.app.location.AmapSample
import com.tripshare.app.location.LocationDisclosureStore
import com.tripshare.app.location.MapConfigState
import com.tripshare.app.location.MapConfigStore
import com.tripshare.app.location.MapProviderSettings
import com.tripshare.app.location.PlaceNameResolver
import com.tripshare.app.location.TripLocationService
import com.tripshare.app.worker.TripSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToLong

private enum class AppPage { HOME, ACTIVE, HISTORY, SETTINGS, SERVER, MAP_SERVICE, TRIP_SETTINGS, LOCATE_MAP }
private enum class PermissionPurpose { NONE, HOME_LOCATE, START, RESUME }
private enum class LocationState { LOCATING, AVAILABLE, UNAVAILABLE }
private enum class PendingAfterConsent { NONE, LOCATE, START }

private const val SUCCESS_GREEN = 0xFF82978C

/** A fresh fix captured for the home status card; reused as the first trip point when still new. */
private data class HomeFix(val sample: AmapSample, val label: String?, val resolvedAtMillis: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripShareApp(repository: TripRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val mapConfig = remember { MapConfigStore(context) }
    var page by rememberSaveable { mutableStateOf(AppPage.HOME.name) }
    var serverSource by rememberSaveable { mutableStateOf(AppPage.HOME.name) }
    var configuredServer by remember { mutableStateOf(repository.serverAddress()) }
    var serverAddressInput by rememberSaveable { mutableStateOf(configuredServer.orEmpty()) }
    var serverAddressMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var savingServerAddress by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableStateOf<LocalTripSummary?>(null) }
    var tripRows by remember { mutableStateOf<List<LocalTripSummary>>(emptyList()) }
    val prefs = remember { context.getSharedPreferences("trip-share-settings", Context.MODE_PRIVATE) }
    var mode by rememberSaveable { mutableStateOf(prefs.getString("mode", TrackingMode.STANDARD.name) ?: TrackingMode.STANDARD.name) }
    var interval by rememberSaveable { mutableStateOf(prefs.getInt("interval", 300)) }
    var maxShare by rememberSaveable { mutableStateOf(prefs.getInt("max_share", 86_400)) }
    var mapProvider by remember { mutableStateOf(mapConfig.selectedProvider()) }
    var mapServiceVersion by remember { mutableStateOf(0) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var consentAccepted by remember { mutableStateOf(LocationDisclosureStore.isAccepted(context)) }
    var showConsent by rememberSaveable { mutableStateOf(false) }
    var pendingAfterConsent by rememberSaveable { mutableStateOf(PendingAfterConsent.NONE.name) }
    var locationAttempted by rememberSaveable { mutableStateOf(false) }
    var homeLocationState by rememberSaveable { mutableStateOf(LocationState.LOCATING.name) }
    var homeFix by remember { mutableStateOf<HomeFix?>(null) }
    var searchTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var showEndConfirm by rememberSaveable { mutableStateOf(false) }
    var showRevokeConfirm by rememberSaveable { mutableStateOf(false) }
    var showRecordModeSheet by rememberSaveable { mutableStateOf(false) }
    var showIntervalSheet by rememberSaveable { mutableStateOf(false) }
    var showMaxShareSheet by rememberSaveable { mutableStateOf(false) }
    var showPermissionSheet by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<LocalTripSummary?>(null) }
    var permissionPurpose by remember { mutableStateOf(PermissionPurpose.NONE) }
    var afterNotificationPermission by remember { mutableStateOf(false) }
    val firstRun = configuredServer == null

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun currentSettings(selectedMode: TrackingMode = TrackingMode.valueOf(mode)): TripSettings {
        val uploadInterval = interval.coerceIn(60, 3_600)
        return TripSettings(
            sampleIntervalSec = if (selectedMode == TrackingMode.DETAILED) 60 else uploadInterval,
            uploadIntervalSec = uploadInterval,
            mode = selectedMode,
            maxShareSeconds = maxShare
        )
    }

    fun saveDefaultSettings(selectedMode: TrackingMode, intervalSeconds: Int, selectedMax: Int) {
        val chosen = intervalSeconds.coerceIn(60, 3_600)
        prefs.edit().putInt("interval", chosen).putString("mode", selectedMode.name).putInt("max_share", selectedMax).apply()
        mode = selectedMode.name
        interval = chosen
        maxShare = selectedMax
    }

    suspend fun locateHome() {
        homeLocationState = LocationState.LOCATING.name
        try {
            val sample = withTimeout(30_000L) { AmapLocationSampler(context).capture(needAddress = true) }
            if (sample == null) {
                homeLocationState = LocationState.UNAVAILABLE.name
                return
            }
            val label = sample.placeLabel?.takeIf(String::isNotBlank)
                ?: PlaceNameResolver.describe(context, sample.latWgs84, sample.lonWgs84)
            homeFix = HomeFix(sample, label, System.currentTimeMillis())
            homeLocationState = LocationState.AVAILABLE.name
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            homeLocationState = LocationState.UNAVAILABLE.name
        }
    }

    suspend fun startTripNow() {
        if (!consentAccepted) {
            showConsent = true
            pendingAfterConsent = PendingAfterConsent.START.name
            return
        }
        if (repository.serverAddress() == null) {
            serverSource = AppPage.HOME.name
            serverAddressInput = ""
            serverAddressMessage = null
            page = AppPage.SERVER.name
            return
        }
        if (!hasLocationPermission()) {
            permissionPurpose = PermissionPurpose.START
            return
        }
        if (!hasNotificationPermission()) {
            showMessage("请允许行程通知，才能看到后台定位状态和结束入口")
            return
        }
        busy = true
        var created: LocalTripSummary? = null
        try {
            repository.apiFactory.assertServerConfigured()
            val freshHomeSample = homeFix
                ?.takeIf { System.currentTimeMillis() - it.resolvedAtMillis <= 90_000L }
                ?.sample
            val sample = freshHomeSample
                ?: withTimeout(45_000L) { AmapLocationSampler(context).capture(needAddress = true) }
                ?: throw IllegalStateException("no valid fix available")
            val label = sample.placeLabel?.takeIf(String::isNotBlank)
                ?: PlaceNameResolver.describe(context, sample.latWgs84, sample.lonWgs84)
            val settings = currentSettings()
            val provider = mapConfig.resolvedProvider()
            val summary = repository.createTrip(
                origin = PlaceMarker(label ?: "当前位置", sample.latWgs84, sample.lonWgs84),
                destination = null,
                settings = settings,
                mapProvider = provider
            )
            created = summary
            val firstPosition = CapturedPosition(
                UUID.randomUUID().toString(), summary.id, sample.latWgs84, sample.lonWgs84,
                sample.capturedAt, sample.accuracyM, sample.speedMps, sample.speedAccuracyMps,
                sample.source, "WGS84"
            )
            repository.saveObservedPosition(firstPosition)
            TripLocationService.start(context, summary.id, firstPosition.capturedAt)
            homeFix = HomeFix(sample, label, System.currentTimeMillis())
            homeLocationState = LocationState.AVAILABLE.name
            active = repository.localTrip(summary.id) ?: summary
            tripRows = repository.allLocalTrips()
            page = AppPage.ACTIVE.name
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            created?.let { trip ->
                runCatching { repository.endTrip(trip.id) }
                TripSyncWorker.enqueue(context, trip.id)
            }
            showMessage("暂时无法开始行程，请检查网络与服务器后重试。")
        } finally {
            busy = false
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true || hasLocationPermission()
        if (!granted) {
            showMessage("需要位置权限才能获取真实位置并开始记录")
            permissionPurpose = PermissionPurpose.NONE
            if (homeFix == null) homeLocationState = LocationState.UNAVAILABLE.name
            return@rememberLauncherForActivityResult
        }
        when (permissionPurpose) {
            PermissionPurpose.HOME_LOCATE -> scope.launch { locateHome() }
            PermissionPurpose.START -> scope.launch { startTripNow() }
            PermissionPurpose.RESUME -> active?.let { trip ->
                scope.launch {
                    TripLocationService.start(context, trip.id, latestLocalTime(repository, trip.id))
                    page = AppPage.ACTIVE.name
                }
            }
            PermissionPurpose.NONE -> Unit
        }
        permissionPurpose = PermissionPurpose.NONE
    }

    fun requestLocation(purpose: PermissionPurpose) {
        permissionPurpose = purpose
        if (hasLocationPermission()) {
            when (purpose) {
                PermissionPurpose.HOME_LOCATE -> scope.launch { locateHome() }
                PermissionPurpose.START -> scope.launch { startTripNow() }
                PermissionPurpose.RESUME -> active?.let { trip ->
                    scope.launch {
                        val time = latestLocalTime(repository, trip.id)
                        TripLocationService.start(context, trip.id, time)
                        page = AppPage.ACTIVE.name
                    }
                }
                PermissionPurpose.NONE -> Unit
            }
            permissionPurpose = PermissionPurpose.NONE
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && afterNotificationPermission) requestLocation(PermissionPurpose.START)
        else if (!granted) showMessage("未允许通知，暂不能启动带常驻状态通知的位置服务")
        afterNotificationPermission = false
    }

    fun requestStart() {
        if (!consentAccepted) {
            showConsent = true
            pendingAfterConsent = PendingAfterConsent.START.name
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            afterNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else requestLocation(PermissionPurpose.START)
    }

    fun openServerPage(source: AppPage) {
        serverSource = source.name
        serverAddressInput = repository.serverAddress().orEmpty()
        serverAddressMessage = null
        page = AppPage.SERVER.name
    }

    // First launch: explain location use once, then locate for the home card.
    LaunchedEffect(firstRun, consentAccepted) {
        if (!firstRun && !consentAccepted && !locationAttempted) {
            showConsent = true
            pendingAfterConsent = PendingAfterConsent.LOCATE.name
        }
    }
    LaunchedEffect(firstRun, consentAccepted, page) {
        if (!firstRun && page == AppPage.HOME.name && consentAccepted && !locationAttempted) {
            locationAttempted = true
            homeLocationState = LocationState.LOCATING.name
            if (hasLocationPermission()) locateHome() else requestLocation(PermissionPurpose.HOME_LOCATE)
        }
    }
    LaunchedEffect(Unit) {
        tripRows = repository.allLocalTrips()
        active = repository.activeTrip()
        // A local active trip is shown as recoverable; recording is resumed only after an explicit tap.
        if (repository.serverAddress() != null) {
            runCatching { repository.refreshTrips() }.onSuccess { rows ->
                tripRows = rows
                active = rows.firstOrNull { it.isActive }
            }
        }
    }
    LaunchedEffect(page) {
        if (page == AppPage.HISTORY.name && repository.serverAddress() != null) {
            runCatching { repository.refreshTrips() }.onSuccess { tripRows = it }
        }
    }

    val effectivePage = if (firstRun) AppPage.SERVER.name else page

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (effectivePage == AppPage.HOME.name) "同行" else pageTitle(effectivePage, settingsTripId, active, firstRun),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (!firstRun && effectivePage != AppPage.HOME.name) IconButton(onClick = {
                        when (effectivePage) {
                            AppPage.MAP_SERVICE.name -> page = AppPage.SETTINGS.name
                            AppPage.TRIP_SETTINGS.name -> page = AppPage.ACTIVE.name
                            AppPage.SERVER.name -> page = serverSource
                            else -> page = AppPage.HOME.name
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    when (effectivePage) {
                        AppPage.HOME.name -> IconButton(onClick = { page = AppPage.SETTINGS.name }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                        AppPage.ACTIVE.name -> active?.let { trip ->
                            TripOverflowMenu(
                                trip = trip,
                                onResume = { requestLocation(PermissionPurpose.RESUME) },
                                onTripSettings = { settingsTripId = trip.id; page = AppPage.TRIP_SETTINGS.name },
                                onAddDestination = { searchTarget = "destination" },
                                onClearDestination = {
                                    busy = true
                                    scope.launch {
                                        runCatching { repository.updateDestination(trip.id, null) }
                                            .onSuccess { updated ->
                                                active = updated
                                                tripRows = repository.allLocalTrips()
                                                showMessage("已清除目的地")
                                            }
                                            .onFailure { showMessage(it.message ?: "清除目的地失败，请检查网络") }
                                        busy = false
                                    }
                                },
                                onRevoke = { showRevokeConfirm = true },
                                onDelete = { deleteCandidate = trip },
                                onHistory = { page = AppPage.HISTORY.name }
                            )
                        }
                        else -> Unit
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (firstRun) {
                ServerAddressPage(
                    firstRun = true,
                    currentAddress = null,
                    value = serverAddressInput,
                    busy = savingServerAddress,
                    message = serverAddressMessage,
                    onValueChange = { value -> serverAddressInput = value; serverAddressMessage = null },
                    onSave = {
                        if (!savingServerAddress) {
                            savingServerAddress = true
                            serverAddressMessage = null
                            scope.launch {
                                runCatching { repository.configureServerAddress(serverAddressInput) }
                                    .onSuccess { saved ->
                                        configuredServer = saved
                                        serverAddressInput = saved
                                        page = AppPage.HOME.name
                                        showMessage("服务器已连接")
                                    }
                                    .onFailure { error ->
                                        serverAddressMessage = error.message ?: "无法连接，请检查服务器地址"
                                    }
                                savingServerAddress = false
                            }
                        }
                    }
                )
            } else {
                when (page) {
                    AppPage.HOME.name -> HomePage(
                        locationState = LocationState.valueOf(homeLocationState),
                        fix = homeFix,
                        activeTrip = active?.takeIf { it.isActive },
                        onStart = {
                            if (active?.isActive == true) page = AppPage.ACTIVE.name else requestStart()
                        },
                        onRelocate = {
                            if (!consentAccepted) {
                                showConsent = true
                                pendingAfterConsent = PendingAfterConsent.LOCATE.name
                            } else {
                                homeFix = null
                                homeLocationState = LocationState.LOCATING.name
                                requestLocation(PermissionPurpose.HOME_LOCATE)
                            }
                        },
                        onOpenCurrentOnMap = { if (homeFix != null) page = AppPage.LOCATE_MAP.name },
                        onHistory = { page = AppPage.HISTORY.name }
                    )
                    AppPage.ACTIVE.name -> active?.let { trip ->
                        ActiveTripPage(
                            trip = trip,
                            repository = repository,
                            onEnd = { showEndConfirm = true },
                            onCopy = { link -> copyText(context, "行程分享链接", link) }
                        )
                    } ?: EmptyState("没有进行中的行程")
                    AppPage.HISTORY.name -> HistoryPage(
                        trips = tripRows,
                        onOpen = { trip -> active = trip; page = AppPage.ACTIVE.name }
                    )
                    AppPage.SETTINGS.name -> SettingsHubPage(
                        selectedMode = TrackingMode.valueOf(mode),
                        intervalMinutes = interval,
                        maxShareSeconds = maxShare,
                        mapProvider = mapProvider,
                        amapReady = mapProvider == MapProvider.AMAP,
                        serverConnected = configuredServer != null,
                        locationGranted = hasLocationPermission(),
                        onServer = { openServerPage(AppPage.SETTINGS) },
                        onMapService = { page = AppPage.MAP_SERVICE.name },
                        onRecordMode = { showRecordModeSheet = true },
                        onInterval = { showIntervalSheet = true },
                        onMaxShare = { showMaxShareSheet = true },
                        onPermissions = { showPermissionSheet = true },
                        onAbout = { showAbout = true }
                    )
                    AppPage.MAP_SERVICE.name -> MapServicePage(
                        store = mapConfig,
                        version = mapServiceVersion,
                        onSaved = {
                            mapProvider = mapConfig.selectedProvider()
                            mapServiceVersion += 1
                            page = AppPage.SETTINGS.name
                            showMessage("地图服务设置已保存")
                        }
                    )
                    AppPage.TRIP_SETTINGS.name -> TripSettingsPage(
                        current = settingsTripId?.let { tripId -> tripRows.firstOrNull { it.id == tripId } ?: active?.takeIf { it.id == tripId } },
                        busy = busy,
                        onSave = { selectedMode, selectedInterval, selectedMax ->
                            val chosen = selectedInterval.coerceIn(60, 3_600)
                            val settings = TripSettings(
                                if (selectedMode == TrackingMode.DETAILED) 60 else chosen,
                                chosen,
                                selectedMode,
                                selectedMax
                            )
                            busy = true
                            scope.launch {
                                val targetId = settingsTripId
                                if (targetId == null) {
                                    saveDefaultSettings(selectedMode, chosen, selectedMax)
                                    busy = false
                                    page = AppPage.HOME.name
                                    showMessage("默认设置已保存")
                                } else {
                                    runCatching { repository.updateSettings(targetId, settings) }
                                        .onSuccess { updated ->
                                            active = updated
                                            tripRows = repository.allLocalTrips()
                                            TripLocationService.settingsUpdated(context, targetId)
                                            page = AppPage.ACTIVE.name
                                            showMessage("行程设置已更新")
                                        }.onFailure { showMessage(it.message ?: "设置更新失败，请检查网络") }
                                    busy = false
                                }
                            }
                        }
                    )
                    AppPage.SERVER.name -> ServerAddressPage(
                        firstRun = false,
                        currentAddress = configuredServer,
                        value = serverAddressInput,
                        busy = savingServerAddress,
                        message = serverAddressMessage,
                        onValueChange = { value -> serverAddressInput = value; serverAddressMessage = null },
                        onSave = {
                            if (!savingServerAddress) {
                                savingServerAddress = true
                                serverAddressMessage = null
                                scope.launch {
                                    runCatching { repository.configureServerAddress(serverAddressInput) }
                                        .onSuccess { saved ->
                                            configuredServer = saved
                                            serverAddressInput = saved
                                            page = serverSource
                                            showMessage("服务器已连接")
                                        }
                                        .onFailure { error ->
                                            serverAddressMessage = error.message ?: "无法连接，请检查服务器地址"
                                        }
                                    savingServerAddress = false
                                }
                            }
                        }
                    )
                    AppPage.LOCATE_MAP.name -> CurrentLocationMapPage(fix = homeFix)
                    else -> Unit
                }
            }
            if (busy && effectivePage != AppPage.TRIP_SETTINGS.name) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (showConsent) {
        val amapLocationAvailable = mapConfig.amapLocationAvailable()
        AlertDialog(
            onDismissRequest = {
                showConsent = false
                pendingAfterConsent = PendingAfterConsent.NONE.name
            },
            title = { Text("位置与隐私说明") },
            text = {
                Text(
                    if (amapLocationAvailable)
                        "开始行程后，同行会在行程期间使用设备定位并同步位置，供家人通过链接查看；结束后会停止记录。若使用高德地图定位，高德会处理设备位置等必要信息，也可以只使用系统定位。"
                    else
                        "开始行程后，同行会在行程期间使用系统定位并同步位置，供家人通过链接查看；结束后会停止记录。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    LocationDisclosureStore.accept(context)
                    consentAccepted = true
                    if (amapLocationAvailable) AmapConsentStore.accept(context)
                    showConsent = false
                    when (pendingAfterConsent) {
                        PendingAfterConsent.LOCATE.name -> {
                            locationAttempted = true
                            homeLocationState = LocationState.LOCATING.name
                            if (hasLocationPermission()) scope.launch { locateHome() }
                            else requestLocation(PermissionPurpose.HOME_LOCATE)
                        }
                        PendingAfterConsent.START.name -> scope.launch { startTripNow() }
                        else -> Unit
                    }
                    pendingAfterConsent = PendingAfterConsent.NONE.name
                }) { Text(if (amapLocationAvailable) "同意并继续" else "我知道了并继续") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showConsent = false
                    if (homeFix == null) homeLocationState = LocationState.UNAVAILABLE.name
                    pendingAfterConsent = PendingAfterConsent.NONE.name
                }) { Text("稍后") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        )
    }

    searchTarget?.let { _ ->
        val trip = active
        if (trip == null || trip.mapProvider != MapProvider.AMAP || !trip.isActive) {
            searchTarget = null
        } else {
            PlaceSearchDialog(
                repository = repository,
                onDismiss = { searchTarget = null },
                onSelect = { place ->
                    searchTarget = null
                    busy = true
                    scope.launch {
                        runCatching { repository.updateDestination(trip.id, place.asMarker()) }
                            .onSuccess { updated ->
                                active = updated
                                tripRows = repository.allLocalTrips()
                                showMessage("目的地已设置为${place.name}")
                            }
                            .onFailure { showMessage(it.message ?: "设置目的地失败，请检查网络") }
                        busy = false
                    }
                }
            )
        }
    }

    if (showEndConfirm) {
        ModalBottomSheet(
            onDismissRequest = { showEndConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outlineVariant) }
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("结束这次行程？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("停止后将不再记录新的位置，已有轨迹仍会保留。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryButton(
                    text = "结束行程",
                    onClick = {
                        val trip = active
                        showEndConfirm = false
                        if (trip != null) {
                            context.stopService(Intent(context, TripLocationService::class.java))
                            busy = true
                            scope.launch {
                                val synced = repository.endTrip(trip.id)
                                TripSyncWorker.enqueue(context, trip.id)
                                active = repository.localTrip(trip.id)
                                tripRows = repository.allLocalTrips()
                                busy = false
                                showMessage(if (synced) "行程已结束" else "行程已在本机结束，网络恢复后会继续更新")
                            }
                        }
                    }
                )
                SecondaryButton(text = "继续共享", onClick = { showEndConfirm = false })
            }
        }
    }

    if (showRevokeConfirm) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirm = false },
            title = { Text("撤销家人查看链接？") },
            text = { Text("撤销后，家人将无法再通过现有链接查看这次行程。") },
            confirmButton = {
                TextButton(onClick = {
                    val trip = active
                    showRevokeConfirm = false
                    if (trip != null) scope.launch {
                        busy = true
                        runCatching { repository.revokeShare(trip.id) }
                            .onSuccess { active = repository.localTrip(trip.id); showMessage("分享链接已撤销") }
                            .onFailure { showMessage(it.message ?: "撤销失败，请检查网络") }
                        busy = false
                    }
                }) { Text("撤销链接") }
            },
            dismissButton = { TextButton(onClick = { showRevokeConfirm = false }) { Text("取消") } },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        )
    }

    if (showRecordModeSheet) {
        OptionSheet(title = "记录模式", onDismiss = { showRecordModeSheet = false }) {
            Text("只影响以后新创建的行程", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceRow(
                selected = TrackingMode.valueOf(mode) == TrackingMode.STANDARD,
                title = "标准模式",
                subtitle = "按照设定间隔记录位置，更省电",
                onClick = {
                    saveDefaultSettings(TrackingMode.STANDARD, interval, maxShare)
                    showRecordModeSheet = false
                }
            )
            ChoiceRow(
                selected = TrackingMode.valueOf(mode) == TrackingMode.DETAILED,
                title = "详细轨迹",
                subtitle = "更频繁记录轨迹，轨迹更完整",
                onClick = {
                    saveDefaultSettings(TrackingMode.DETAILED, interval, maxShare)
                    showRecordModeSheet = false
                }
            )
        }
    }

    if (showIntervalSheet) {
        IntervalSheet(
            currentMinutes = interval / 60,
            onSelect = { minutes ->
                saveDefaultSettings(TrackingMode.valueOf(mode), minutes * 60, maxShare)
                showIntervalSheet = false
            },
            onDismiss = { showIntervalSheet = false }
        )
    }

    if (showMaxShareSheet) {
        OptionSheet(title = "最长共享时间", onDismiss = { showMaxShareSheet = false }) {
            Text("只影响以后新创建的行程", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3_600, 21_600, 43_200, 86_400).forEach { seconds ->
                    IntervalChoice(
                        maxShareLabel(seconds),
                        maxShare == seconds
                    ) {
                        saveDefaultSettings(TrackingMode.valueOf(mode), interval, seconds)
                        showMaxShareSheet = false
                    }
                }
            }
        }
    }

    if (showPermissionSheet) {
        OptionSheet(title = "权限状态", onDismiss = { showPermissionSheet = false }) {
            PermissionStatusRow("定位权限", hasLocationPermission())
            PermissionStatusRow("通知权限", hasNotificationPermission())
            Text(
                "定位权限用于记录行程位置；通知权限用于显示后台共享状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SecondaryButton(
                text = "前往系统设置",
                onClick = {
                    showPermissionSheet = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    )
                }
            )
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于同行") },
            text = { Text("一款为旅途位置分享而设计的轻量工具。只有你主动开始行程后，位置才会分享给家人。") },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("知道了") } },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        )
    }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除行程记录？") },
            text = { Text("这会从服务器删除行程和全部轨迹数据，无法恢复。") },
            confirmButton = {
                Button(onClick = {
                    deleteCandidate = null
                    busy = true
                    scope.launch {
                        runCatching { repository.deleteTrip(candidate.id) }
                            .onSuccess {
                                tripRows = repository.allLocalTrips()
                                if (active?.id == candidate.id) {
                                    active = null
                                    page = AppPage.HOME.name
                                }
                                showMessage("行程记录已删除")
                            }.onFailure { showMessage(it.message ?: "删除失败，请检查网络") }
                        busy = false
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("取消") } },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        )
    }
}

@Composable
private fun HomePage(
    locationState: LocationState,
    fix: HomeFix?,
    activeTrip: LocalTripSummary?,
    onStart: () -> Unit,
    onRelocate: () -> Unit,
    onOpenCurrentOnMap: () -> Unit,
    onHistory: () -> Unit
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activeTrip?.id) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp)) {
        LocationStatusCard(
            locationState = locationState,
            fix = fix,
            now = now,
            onRelocate = onRelocate,
            onOpenCurrentOnMap = onOpenCurrentOnMap
        )
        Spacer(Modifier.weight(1f))
        if (activeTrip == null) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("开始一次位置共享", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "上车后开始，家人打开链接即可看到你的位置",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val currentState = tripUiState(activeTrip, activeTrip.latestPositionAt, now)
                TripStatus(currentState, activeTrip.latestPositionAt, now)
                TripHeading(activeTrip)
                Text("行程正在共享", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = if (activeTrip != null) "查看共享中" else "开始共享",
                onClick = onStart
            )
            TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
                Text("查看历史行程", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun LocationStatusCard(
    locationState: LocationState,
    fix: HomeFix?,
    now: Long,
    onRelocate: () -> Unit,
    onOpenCurrentOnMap: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (locationState == LocationState.AVAILABLE && fix != null) Modifier.clickable(onClick = onOpenCurrentOnMap) else Modifier),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(when (locationState) {
                    LocationState.AVAILABLE -> Color(SUCCESS_GREEN)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }))
                Text(
                    when (locationState) {
                        LocationState.AVAILABLE -> "定位已就绪"
                        LocationState.LOCATING -> "正在获取当前位置…"
                        LocationState.UNAVAILABLE -> "暂时无法获取位置"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Text("当前位置", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Default.LocationOn, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)
                )
                Text(
                    when (locationState) {
                        LocationState.AVAILABLE -> fix?.label ?: "当前位置已获取"
                        else -> "正在获取当前位置…"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (locationState == LocationState.AVAILABLE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            if (locationState == LocationState.AVAILABLE && fix != null) {
                Text(locationAgeLabel(fix.resolvedAtMillis, now), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (locationState == LocationState.UNAVAILABLE) {
                TextButton(onClick = onRelocate) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text("重新定位", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

private fun locationAgeLabel(resolvedAtMillis: Long, now: Long): String {
    val elapsedMinutes = ((now - resolvedAtMillis) / 60_000L).coerceAtLeast(0)
    return if (elapsedMinutes < 1) "刚刚定位" else "${elapsedMinutes} 分钟前定位"
}

@Composable
private fun ServerAddressPage(
    firstRun: Boolean,
    currentAddress: String?,
    value: String,
    busy: Boolean,
    message: String?,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(if (firstRun) "连接服务器" else "服务器", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("连接你的位置共享服务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("服务器地址", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        placeholder = { Text("https://example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            capitalization = KeyboardCapitalization.None
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    when {
                        busy -> VerificationStatusRow("验证中…", MaterialTheme.colorScheme.onSurfaceVariant)
                        message != null -> VerificationStatusRow(message, MaterialTheme.colorScheme.onSurface)
                        !firstRun && currentAddress != null && value.trim() == currentAddress.trim() ->
                            VerificationStatusRow("上次验证成功", Color(SUCCESS_GREEN))
                        else -> VerificationStatusRow("尚未验证", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            InfoHintRow("公网地址需使用 HTTPS")
            InfoHintRow("若设备已有未完成行程，暂时无法切换服务器")
        }
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            PrimaryButton(
                text = if (busy) "验证中…" else "验证并保存",
                onClick = onSave,
                enabled = !busy && value.isNotBlank()
            )
        }
    }
}

@Composable
private fun VerificationStatusRow(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoHintRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MapServicePage(store: MapConfigStore, version: Int, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selected by remember(version) { mutableStateOf(store.selectedProvider()) }
    var androidKey by remember(version) { mutableStateOf(store.amapAndroidKey().orEmpty()) }
    var webKey by remember(version) { mutableStateOf(store.amapWebServiceKey().orEmpty()) }
    var showAndroidKey by remember { mutableStateOf(false) }
    var showWebKey by remember { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("选择用于地图显示与地点能力的服务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProviderCard(
                selected = selected == MapProvider.OSM,
                title = "基础模式",
                subtitle = "OpenStreetMap",
                description = "无需额外配置，提供地图与轨迹显示",
                onClick = { selected = MapProvider.OSM }
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(
                    1.dp,
                    if (selected == MapProvider.AMAP) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column {
                    ProviderCard(
                        selected = selected == MapProvider.AMAP,
                        title = "增强模式",
                        subtitle = "高德地图",
                        description = "提供地点名称、POI 搜索与目的地设置",
                        borderless = true,
                        onClick = { selected = MapProvider.AMAP }
                    )
                    if (selected == MapProvider.AMAP) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            SecretField(
                                label = "高德 Android Key",
                                value = androidKey,
                                visible = showAndroidKey,
                                onValueChange = { androidKey = it; error = null },
                                onToggleVisible = { showAndroidKey = !showAndroidKey }
                            )
                            SecretField(
                                label = "高德 Web 服务 Key",
                                value = webKey,
                                visible = showWebKey,
                                onValueChange = { webKey = it; error = null },
                                onToggleVisible = { showWebKey = !showWebKey }
                            )
                            val state = MapProviderSettings.configState(androidKey, webKey)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (state == MapConfigState.READY) Color(SUCCESS_GREEN) else MaterialTheme.colorScheme.onSurfaceVariant))
                                Text(
                                    if (state == MapConfigState.READY) "高德服务已配置" else "配置尚未完成",
                                    color = if (state == MapConfigState.READY) Color(SUCCESS_GREEN) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            PrimaryButton(
                text = if (saving) "正在保存…" else "保存设置",
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (androidKey.isNotBlank() && webKey.isNotBlank()) {
                                    store.saveAmapKeys(androidKey, webKey)
                                }
                                store.saveSelectedProvider(selected)
                            }
                            error = null
                            onSaved()
                        } catch (failure: Exception) {
                            error = failure.message ?: "保存失败，请重试"
                        } finally {
                            saving = false
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun ProviderCard(
    selected: Boolean,
    title: String,
    subtitle: String,
    description: String,
    borderless: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = if (borderless) RectangleShape else RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.padding(top = 3.dp).size(20.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    visible: Boolean,
    onValueChange: (String) -> Unit,
    onToggleVisible: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("请输入$label") },
            singleLine = true,
            visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleVisible, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (visible) "隐藏" else "显示",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                cursorColor = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionSheet(title: String, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outlineVariant) }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalSheet(currentMinutes: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(currentMinutes !in listOf(1, 5, 10, 15)) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outlineVariant) }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("更新间隔", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("只影响以后新创建的行程", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10, 15).forEach { minutes ->
                    IntervalChoice("$minutes 分钟", currentMinutes == minutes && !showCustomInput) {
                        onSelect(minutes)
                    }
                }
            }
            IntervalChoice("自定义", showCustomInput) { showCustomInput = true }
            if (showCustomInput) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { text -> custom = text.filter(Char::isDigit).take(2) },
                        label = { Text("自定义间隔（分钟）") },
                        placeholder = { Text("1 到 60") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    PrimaryButton(
                        text = "确定",
                        enabled = custom.toIntOrNull() in 1..60,
                        onClick = { custom.toIntOrNull()?.let(onSelect) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(
                    if (granted) Color(SUCCESS_GREEN) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Text(if (granted) "已开启" else "未开启", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(20.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun IntervalChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
        shape = CircleShape
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ActiveTripPage(
    trip: LocalTripSummary, repository: TripRepository, onEnd: () -> Unit, onCopy: (String) -> Unit
) {
    val localPoints by repository.observePoints(trip.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val pending by repository.observePendingCount(trip.id).collectAsStateWithLifecycle(initialValue = 0)
    val rejected by repository.observeRejectedCount(trip.id).collectAsStateWithLifecycle(initialValue = 0)
    val latest = localPoints.lastOrNull()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var copied by rememberSaveable(trip.id) { mutableStateOf(false) }
    LaunchedEffect(trip.id) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_000)
            copied = false
        }
    }
    val latestAt = latest?.capturedAt ?: trip.latestPositionAt
    val uiState = tripUiState(trip, latestAt, now)
    val shareUrl = trip.shareUrl?.takeIf { trip.canShare }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TripStatus(uiState, latestAt, now)
                TripHeading(trip)
                Text(
                    "已持续 ${formatDuration(trip.startedAt, trip.endedAt, now)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TripMap(shareUrl = shareUrl, tripEnded = !trip.isActive)
            if (trip.isActive) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Text(
                            trip.latestPositionLabel?.takeIf(String::isNotBlank)?.let { "当前位置 · $it" } ?: "当前位置",
                            fontWeight = FontWeight.Medium
                        )
                    }
                    latest?.takeIf(::hasReliableSpeed)?.takeIf { uiState == TripUiState.ACTIVE }?.let {
                        Text(
                            "时速约 ${((it.speedMps ?: 0.0) * 3.6).roundToLong()} km/h",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 32.dp)
                        )
                    }
                    if (pending > 0) {
                        Text(
                            "$pending 个位置等待同步",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 32.dp)
                        )
                    }
                    if (pending == 0 && rejected > 0) {
                        Text(
                            "$rejected 个位置未能同步",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 32.dp)
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                        InfoRow("最后更新", formatEndedClock(latestAt))
                        if (trip.destination != null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (trip.destination != null) InfoRow("到达地点", trip.destination.name)
                    }
                }
                Text("本次行程已完成，仍可查看历史轨迹。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = if (copied) "已复制" else "复制链接",
                onClick = {
                    if (shareUrl != null) {
                        onCopy(shareUrl)
                        copied = true
                    }
                },
                enabled = shareUrl != null
            )
            if (trip.isActive) SecondaryButton(text = "结束行程", onClick = onEnd)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun formatEndedClock(value: String?): String = runCatching {
    val zoned = Instant.parse(value).atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now(ZoneId.systemDefault())
    val dateLabel = when (zoned.toLocalDate()) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${zoned.monthValue}月${zoned.dayOfMonth}日"
    }
    "$dateLabel ${DateTimeFormatter.ofPattern("HH:mm").format(zoned)}"
}.getOrDefault("--")

@Composable
private fun TripMap(shareUrl: String?, tripEnded: Boolean) {
    val mapUrl = shareUrl?.let { if (it.contains('?')) "$it&embed=map" else "$it?embed=map" }
    Surface(
        modifier = Modifier.fillMaxWidth().height(380.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        if (mapUrl != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext -> WebView(viewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(false)
                    isVerticalScrollBarEnabled = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient()
                    loadUrl(mapUrl)
                } },
                update = { view -> if (view.url != mapUrl) view.loadUrl(mapUrl) }
            )
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                Text(if (tripEnded) "地图暂时无法打开" else "等待可查看的位置", fontWeight = FontWeight.Medium)
                Text("已记录的位置会保留在行程中。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CurrentLocationMapPage(fix: HomeFix?) {
    val url = fix?.let {
        "file:///android_asset/current_map.html?lat=${it.sample.latWgs84}&lon=${it.sample.lonWgs84}"
    } ?: "file:///android_asset/current_map.html"
    Surface(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext -> WebView(viewContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportZoom(false)
                isVerticalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                loadUrl(url)
            } },
            update = { view -> if (view.url != url) view.loadUrl(url) }
        )
    }
}

@Composable
private fun HistoryPage(
    trips: List<LocalTripSummary>,
    onOpen: (LocalTripSummary) -> Unit
) {
    var filter by rememberSaveable { mutableStateOf("ALL") }
    val visibleTrips = when (filter) {
        "ACTIVE" -> trips.filter { it.isActive }
        "ENDED" -> trips.filterNot { it.isActive }
        else -> trips
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp)) {
            listOf("ALL" to "全部", "ACTIVE" to "共享中", "ENDED" to "已结束").forEach { (key, label) ->
                val selected = filter == key
                Surface(
                    modifier = Modifier.weight(1f).clip(CircleShape).clickable { filter = key },
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(label, modifier = Modifier.padding(vertical = 11.dp), textAlign = TextAlign.Center, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (visibleTrips.isEmpty()) EmptyState(if (trips.isEmpty()) "还没有历史行程" else "此筛选下没有行程")
        else LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = WindowInsets.navigationBars.asPaddingValues()
        ) {
            items(visibleTrips, key = { it.id }) { trip -> TripHistoryItem(trip, onClick = { onOpen(trip) }) }
        }
    }
}

@Composable
private fun TripHistoryItem(trip: LocalTripSummary, onClick: () -> Unit) {
    val active = trip.isActive
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(if (trip.destination != null) Icons.Default.Route else Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tripHeading(trip), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    if (active) {
                        "进行中 · ${formatDuration(trip.startedAt, trip.endedAt, System.currentTimeMillis())}"
                    } else {
                        "${formatHistoryTimestamp(trip.startedAt)} · ${formatDuration(trip.startedAt, trip.endedAt, System.currentTimeMillis())}"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    if (active) "共享中" else "已结束",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Settings hub: every technical option lives here, one Bottom Sheet per entry. */
@Composable
private fun SettingsHubPage(
    selectedMode: TrackingMode,
    intervalMinutes: Int,
    maxShareSeconds: Int,
    mapProvider: MapProvider,
    amapReady: Boolean,
    serverConnected: Boolean,
    locationGranted: Boolean,
    onServer: () -> Unit,
    onMapService: () -> Unit,
    onRecordMode: () -> Unit,
    onInterval: () -> Unit,
    onMaxShare: () -> Unit,
    onPermissions: () -> Unit,
    onAbout: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsSectionLabel("服务")
        SettingsCard {
            SettingsRow("服务器", if (serverConnected) "已连接" else "未连接", showDot = serverConnected, onClick = onServer)
        }
        SettingsSectionLabel("地图")
        SettingsCard {
            SettingsRow(
                "地图服务",
                if (mapProvider == MapProvider.AMAP) "增强模式 · 高德地图" else "基础模式 · OpenStreetMap",
                showDot = false,
                onClick = onMapService
            )
        }
        SettingsSectionLabel("位置记录")
        SettingsCard {
            SettingsRow("记录模式", if (selectedMode == TrackingMode.DETAILED) "详细轨迹" else "标准模式", onClick = onRecordMode)
            SettingsDivider()
            SettingsRow("更新间隔", "${intervalMinutes.coerceAtLeast(1)} 分钟", onClick = onInterval)
            SettingsDivider()
            SettingsRow("最长共享时间", maxShareLabel(maxShareSeconds), onClick = onMaxShare)
        }
        SettingsSectionLabel("关于")
        SettingsCard {
            SettingsRow("权限状态", if (locationGranted) "定位已开启" else "未开启", onClick = onPermissions)
            SettingsDivider()
            SettingsRow("关于同行", "", onClick = onAbout)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsSectionLabel(label: String) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 18.dp), content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsRow(title: String, value: String, showDot: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        if (showDot) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(SUCCESS_GREEN)))
            Spacer(Modifier.width(8.dp))
        }
        if (value.isNotEmpty()) {
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TripOverflowMenu(
    trip: LocalTripSummary,
    onResume: () -> Unit,
    onTripSettings: () -> Unit,
    onAddDestination: () -> Unit,
    onClearDestination: () -> Unit,
    onRevoke: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多行程操作") }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (trip.isActive) {
                DropdownItem("继续后台记录", Icons.Default.MyLocation) { expanded = false; onResume() }
                DropdownItem("行程设置", Icons.Default.Settings) { expanded = false; onTripSettings() }
                if (trip.mapProvider == MapProvider.AMAP) {
                    if (trip.destination == null) {
                        DropdownItem("添加目的地", Icons.Default.LocationOn) { expanded = false; onAddDestination() }
                    } else {
                        DropdownItem("修改目的地", Icons.Default.LocationOn) { expanded = false; onAddDestination() }
                        DropdownItem("清除目的地", Icons.Default.DeleteOutline) { expanded = false; showClearConfirm = true }
                    }
                }
                if (trip.canShare) DropdownItem("撤销查看链接", Icons.Default.Link) { expanded = false; onRevoke() }
            } else {
                DropdownItem("删除行程", Icons.Default.DeleteOutline) { expanded = false; onDelete() }
            }
            DropdownItem("历史行程", Icons.Default.History) { expanded = false; onHistory() }
        }
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清除目的地？") },
            text = { Text("清除后行程继续正常记录位置，仅不再显示目的地。") },
            confirmButton = {
                TextButton(onClick = { showClearConfirm = false; onClearDestination() }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        )
    }
}

@Composable
private fun DropdownItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(22.dp)
    ) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) { Text(text, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun TripStatus(state: TripUiState, latestAt: String?, now: Long) {
    val label = when (state) {
        TripUiState.ACTIVE -> "正常共享中"
        TripUiState.STALE -> "暂未更新"
        TripUiState.ENDED -> "已结束"
    }
    val detail = latestAt?.let { " · ${relativeUpdate(it, now)}" }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(
                if (state == TripUiState.ACTIVE) Color(SUCCESS_GREEN) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(label + detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TripHeading(trip: LocalTripSummary) {
    val route = when {
        trip.origin != null && trip.destination != null -> "${trip.origin.name} → ${trip.destination.name}"
        trip.origin != null -> "从${trip.origin.name}出发"
        else -> trip.title.ifBlank { "我的行程" }
    }
    Text(route, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaceSearchDialog(repository: TripRepository, onDismiss: () -> Unit, onSelect: (AmapPlace) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AmapPlace>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索地点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, label = { Text("地点名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    IconButton(onClick = {
                        if (query.isBlank()) return@IconButton
                        loading = true
                        message = null
                        scope.launch {
                            runCatching { repository.apiFactory.searchPlaces(query.trim()) }
                                .onSuccess { found -> results = found; if (found.isEmpty()) message = "没有找到可用地点" }
                                .onFailure { message = it.message ?: "搜索失败" }
                            loading = false
                        }
                    }, enabled = !loading && query.isNotBlank()) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.MyLocation, "搜索")
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (results.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(results) { place ->
                            Column(Modifier.fillMaxWidth().clickable { onSelect(place) }.padding(vertical = 9.dp)) {
                                Text(place.name, fontWeight = FontWeight.Medium)
                                place.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                HorizontalDivider(Modifier.padding(top = 7.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    )
}

/** Per-trip recording settings (defaults hub only affects future trips; this edits one running trip). */
@Composable
private fun TripSettingsPage(current: LocalTripSummary?, busy: Boolean, onSave: (TrackingMode, Int, Int) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("trip-share-settings", Context.MODE_PRIVATE) }
    var mode by remember(current?.id) { mutableStateOf(current?.mode ?: runCatching { TrackingMode.valueOf(prefs.getString("mode", TrackingMode.STANDARD.name)!!) }.getOrDefault(TrackingMode.STANDARD)) }
    var interval by remember(current?.id) { mutableStateOf(current?.let { if (it.mode == TrackingMode.DETAILED) it.uploadIntervalSec else it.sampleIntervalSec } ?: prefs.getInt("interval", 300)) }
    var custom by remember(current?.id) { mutableStateOf("") }
    var maxShare by remember(current?.id) { mutableStateOf(current?.maxShareSeconds ?: prefs.getInt("max_share", 86_400)) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(if (current == null) "为新行程选择默认记录方式。" else "调整这次行程的位置记录方式。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("位置记录模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ChoiceRow(
                    selected = mode == TrackingMode.STANDARD,
                    title = "标准模式",
                    subtitle = "按照设定间隔记录位置，较省电。",
                    onClick = { mode = TrackingMode.STANDARD }
                )
                ChoiceRow(
                    selected = mode == TrackingMode.DETAILED,
                    title = "详细轨迹",
                    subtitle = "更频繁记录轨迹，耗电更高。",
                    onClick = { mode = TrackingMode.DETAILED }
                )
                Text("位置更新间隔", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 300, 600, 900).forEach { seconds ->
                        val label = when (seconds) { 60 -> "1 分"; 300 -> "5 分"; 600 -> "10 分"; else -> "15 分" }
                        IntervalChoice(label, interval == seconds && custom.isBlank()) { interval = seconds; custom = "" }
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { text -> custom = text.filter(Char::isDigit).take(2) },
                    label = { Text("自定义间隔（分钟）") },
                    placeholder = { Text("默认 5 分钟") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        cursorColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text("最长共享时间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3_600, 21_600, 43_200, 86_400).forEach { seconds ->
                        IntervalChoice(
                            when (seconds) { 3_600 -> "1 小时"; 21_600 -> "6 小时"; 43_200 -> "12 小时"; else -> "24 小时" },
                            maxShare == seconds
                        ) { maxShare = seconds }
                    }
                }
            }
        }
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            PrimaryButton(
                text = if (busy) "正在保存…" else "保存设置",
                onClick = { onSave(mode, custom.toIntOrNull()?.coerceIn(1, 60)?.times(60) ?: interval, maxShare) },
                enabled = !busy
            )
        }
    }
}

private enum class TripUiState { ACTIVE, STALE, ENDED }

private fun pageTitle(page: String, settingsTripId: String?, active: LocalTripSummary?, firstRun: Boolean): String = when (page) {
    AppPage.HOME.name -> "同行"
    AppPage.ACTIVE.name -> if (active?.isActive == true) "共享中" else "已结束"
    AppPage.HISTORY.name -> "历史行程"
    AppPage.SETTINGS.name -> if (settingsTripId == null) "设置" else "行程设置"
    AppPage.SERVER.name -> if (firstRun) "连接服务器" else "服务器"
    AppPage.MAP_SERVICE.name -> "地图服务"
    AppPage.TRIP_SETTINGS.name -> "行程设置"
    AppPage.LOCATE_MAP.name -> "当前位置"
    else -> "同行"
}

private fun tripUiState(trip: LocalTripSummary, latestAt: String?, now: Long): TripUiState {
    if (!trip.isActive) return TripUiState.ENDED
    val captured = latestAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return TripUiState.ACTIVE
    val staleAfterMillis = (trip.uploadIntervalSec * 2L + 120L) * 1_000L
    return if (now - captured > staleAfterMillis) TripUiState.STALE else TripUiState.ACTIVE
}

private fun tripHeading(trip: LocalTripSummary): String = when {
    trip.origin != null && trip.destination != null -> "${trip.origin.name} → ${trip.destination.name}"
    trip.origin != null -> "从${trip.origin.name}出发"
    else -> trip.title.ifBlank { "我的行程" }
}

private fun relativeUpdate(value: String, now: Long): String = runCatching {
    val elapsed = ((now - Instant.parse(value).toEpochMilli()) / 1_000L).coerceAtLeast(0)
    when {
        elapsed < 60 -> "刚刚更新"
        elapsed < 3_600 -> "${elapsed / 60} 分钟前更新"
        elapsed < 86_400 -> "${elapsed / 3_600} 小时前更新"
        else -> "${elapsed / 86_400} 天前更新"
    }
}.getOrDefault("最近更新")

private fun formatHistoryTimestamp(value: String): String = runCatching {
    val zoned = Instant.parse(value).atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now(ZoneId.systemDefault())
    val dateLabel = when (zoned.toLocalDate()) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${zoned.monthValue}月${zoned.dayOfMonth}日"
    }
    "$dateLabel ${zoned.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}.getOrDefault(value)

private fun formatDuration(startedAt: String, endedAt: String?, now: Long): String = runCatching {
    val start = Instant.parse(startedAt)
    val end = endedAt?.let(Instant::parse) ?: Instant.ofEpochMilli(now)
    val minutes = Duration.between(start, end).toMinutes().coerceAtLeast(0)
    val days = minutes / 1_440
    val hours = (minutes % 1_440) / 60
    val remainder = minutes % 60
    when {
        days > 0 -> "$days 天 ${hours} 小时"
        hours > 0 -> "$hours 小时 ${remainder} 分"
        else -> "$remainder 分钟"
    }
}.getOrDefault("--")

private fun maxShareLabel(seconds: Int): String = when {
    seconds <= 3_600 -> "1 小时"
    seconds <= 21_600 -> "6 小时"
    seconds <= 43_200 -> "12 小时"
    else -> "24 小时"
}

private fun hasReliableSpeed(point: PositionEntity): Boolean {
    val speed = point.speedMps ?: return false
    if (!speed.isFinite() || speed !in 0.0..120.0) return false
    val accuracy = point.speedAccuracyMps
    return accuracy == null || (accuracy.isFinite() && accuracy in 0.0..5.0)
}

private fun copyText(context: Context, label: String, value: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
}

private suspend fun latestLocalTime(repository: TripRepository, tripId: String): String {
    return withContext(Dispatchers.IO) { repository.latestPoint(tripId)?.capturedAt ?: Instant.now().toString() }
}
