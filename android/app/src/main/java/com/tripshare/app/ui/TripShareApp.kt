package com.tripshare.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tripshare.app.BuildConfig
import com.tripshare.app.data.CapturedPosition
import com.tripshare.app.data.LocalTripSummary
import com.tripshare.app.data.PlaceMarker
import com.tripshare.app.data.TrackingMode
import com.tripshare.app.data.TripRepository
import com.tripshare.app.data.TripSettings
import com.tripshare.app.data.local.PositionEntity
import com.tripshare.app.location.AmapConsentStore
import com.tripshare.app.location.LocationDisclosureStore
import com.tripshare.app.location.AmapLocationSampler
import com.tripshare.app.location.AmapPlace
import com.tripshare.app.location.TripLocationService
import com.tripshare.app.worker.TripSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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

private enum class AppPage { HOME, CREATE, ACTIVE, HISTORY, SETTINGS }
private enum class PermissionPurpose { NONE, AUTO_ORIGIN, RELOCATE, START, RESUME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripShareApp(repository: TripRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf(AppPage.HOME.name) }
    var active by remember { mutableStateOf<LocalTripSummary?>(null) }
    var tripRows by remember { mutableStateOf<List<LocalTripSummary>>(emptyList()) }
    var origin by remember { mutableStateOf<PlaceMarker?>(null) }
    var originManuallyChanged by rememberSaveable { mutableStateOf(false) }
    var destination by remember { mutableStateOf<PlaceMarker?>(null) }
    val savedDefaults = remember { context.getSharedPreferences("trip-share-settings", Context.MODE_PRIVATE) }
    var mode by rememberSaveable { mutableStateOf(savedDefaults.getString("mode", TrackingMode.STANDARD.name) ?: TrackingMode.STANDARD.name) }
    var interval by rememberSaveable { mutableStateOf(savedDefaults.getInt("interval", 300)) }
    var customInterval by rememberSaveable { mutableStateOf("") }
    var maxShare by rememberSaveable { mutableStateOf(savedDefaults.getInt("max_share", 86_400)) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var consentAccepted by remember { mutableStateOf(LocationDisclosureStore.isAccepted(context)) }
    var amapConsentAccepted by remember { mutableStateOf(AmapConsentStore.isAccepted(context)) }
    var showConsent by rememberSaveable { mutableStateOf(false) }
    var createError by rememberSaveable { mutableStateOf<String?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var locationAttempted by rememberSaveable { mutableStateOf(false) }
    var originLocationLoading by rememberSaveable { mutableStateOf(false) }
    var originLocationFailed by rememberSaveable { mutableStateOf(false) }
    var searchTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var showEndConfirm by rememberSaveable { mutableStateOf(false) }
    var showRevokeConfirm by rememberSaveable { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<LocalTripSummary?>(null) }
    var permissionPurpose by remember { mutableStateOf(PermissionPurpose.NONE) }
    var afterNotificationPermission by remember { mutableStateOf(false) }

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun currentSettings(selectedMode: TrackingMode = TrackingMode.valueOf(mode)): TripSettings {
        val chosen = customInterval.toIntOrNull()?.coerceIn(1, 60)?.times(60) ?: interval
        val uploadInterval = chosen.coerceIn(60, 3_600)
        return TripSettings(
            sampleIntervalSec = if (selectedMode == TrackingMode.DETAILED) 60 else uploadInterval,
            uploadIntervalSec = uploadInterval,
            mode = selectedMode,
            maxShareSeconds = maxShare
        )
    }

    suspend fun locateOrigin(allowReplace: Boolean) {
        if (!consentAccepted) {
            showConsent = true
            return
        }
        originLocationLoading = true
        originLocationFailed = false
        busy = true
        try {
            val sample = withTimeout(45_000L) { AmapLocationSampler(context).capture(needAddress = true) }
                ?: throw IllegalStateException("暂时无法获取有效位置，请重试或手动搜索出发地")
            if (!allowReplace && originManuallyChanged) return
            val label = sample.placeLabel?.takeIf(String::isNotBlank) ?: "当前位置"
            origin = PlaceMarker(label, sample.latWgs84, sample.lonWgs84)
            originLocationFailed = false
            if (allowReplace) originManuallyChanged = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            originLocationFailed = true
            showMessage("暂时无法获取位置，请稍后重试或手动选择出发地。")
        } finally {
            originLocationLoading = false
            busy = false
        }
    }

    suspend fun startTripNow() {
        if (!consentAccepted) {
            showConsent = true
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
        createError = null
        busy = true
        var created: LocalTripSummary? = null
        try {
            repository.apiFactory.assertServerConfigured()
            val actual = withTimeout(45_000L) { AmapLocationSampler(context).capture(needAddress = false) }
                ?: throw IllegalStateException("目前无法获取有效定位，行程尚未创建。请检查定位权限、定位服务和高德 Android Key")
            val chosenMode = TrackingMode.valueOf(mode)
            val settings = currentSettings(chosenMode)
            val summary = repository.createTrip(origin, destination, settings)
            created = summary
            val firstPosition = CapturedPosition(
                UUID.randomUUID().toString(), summary.id, actual.latWgs84, actual.lonWgs84,
                actual.capturedAt, actual.accuracyM, actual.speedMps, actual.speedAccuracyMps,
                actual.source, "WGS84"
            )
            repository.saveObservedPosition(firstPosition)
            TripLocationService.start(context, summary.id, firstPosition.capturedAt)
            active = repository.localTrip(summary.id) ?: summary
            page = AppPage.ACTIVE.name
        } catch (error: Exception) {
            created?.let { trip ->
                repository.endTrip(trip.id)
                TripSyncWorker.enqueue(context, trip.id)
            }
            createError = "暂时无法连接服务器，请稍后重试。"
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
            return@rememberLauncherForActivityResult
        }
        when (permissionPurpose) {
            PermissionPurpose.AUTO_ORIGIN -> scope.launch { locateOrigin(allowReplace = false) }
            PermissionPurpose.RELOCATE -> scope.launch { locateOrigin(allowReplace = true) }
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
                PermissionPurpose.AUTO_ORIGIN -> scope.launch { locateOrigin(allowReplace = false) }
                PermissionPurpose.RELOCATE -> scope.launch { locateOrigin(allowReplace = true) }
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
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
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
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            afterNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else requestLocation(PermissionPurpose.START)
    }

    LaunchedEffect(page, consentAccepted) {
        if (page == AppPage.CREATE.name && consentAccepted && !locationAttempted) {
            locationAttempted = true
            if (hasLocationPermission()) locateOrigin(allowReplace = false)
            else requestLocation(PermissionPurpose.AUTO_ORIGIN)
        }
    }

    LaunchedEffect(Unit) {
        tripRows = repository.allLocalTrips()
        active = repository.activeTrip()
        // A local active trip is shown as recoverable; recording is resumed only after an explicit tap.
        runCatching { repository.refreshTrips() }.onSuccess { rows ->
            tripRows = rows
            active = rows.firstOrNull { it.isActive }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (page == AppPage.HOME.name) "同行" else pageTitle(page, settingsTripId, active),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (page != AppPage.HOME.name) IconButton(onClick = {
                        if (page == AppPage.SETTINGS.name && settingsTripId != null) page = AppPage.ACTIVE.name
                        else page = AppPage.HOME.name
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    when (page) {
                        AppPage.HOME.name -> HomeOverflowMenu(
                            onSettings = { settingsTripId = null; page = AppPage.SETTINGS.name },
                            onAbout = { showAbout = true }
                        )
                        AppPage.ACTIVE.name -> active?.let { trip ->
                            TripOverflowMenu(
                                trip = trip,
                                onSettings = { settingsTripId = trip.id; page = AppPage.SETTINGS.name },
                                onResume = { requestLocation(PermissionPurpose.RESUME) },
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
            when (page) {
                AppPage.HOME.name -> HomePage(
                    active = active,
                    onCreate = {
                        if (active?.isActive == true) showMessage("请先结束当前行程，再创建新行程")
                        else {
                            createError = null
                            origin = null
                            originManuallyChanged = false
                            originLocationLoading = false
                            originLocationFailed = false
                            destination = null
                            locationAttempted = false
                            page = AppPage.CREATE.name
                            if (!consentAccepted) showConsent = true
                        }
                    },
                    onOpenActive = { active = it; page = AppPage.ACTIVE.name },
                    onHistory = {
                        page = AppPage.HISTORY.name
                        scope.launch { runCatching { repository.refreshTrips() }.onSuccess { tripRows = it } }
                    }
                )
                AppPage.CREATE.name -> CreateTripPage(
                    origin = origin,
                    originLocationPlaceholder = when {
                        originLocationLoading -> "正在获取当前位置"
                        originLocationFailed -> "定位失败，点击重试或搜索"
                        locationAttempted -> "未获取位置，点击重试或搜索"
                        else -> "正在获取当前位置"
                    },
                    destination = destination,
                    mode = TrackingMode.valueOf(mode),
                    interval = interval,
                    customInterval = customInterval,
                    maxShare = maxShare,
                    busy = busy,
                    error = createError,
                    onOrigin = { searchTarget = "origin" },
                    onDestination = { searchTarget = "destination" },
                    onRelocate = {
                        if (!consentAccepted) showConsent = true else requestLocation(PermissionPurpose.RELOCATE)
                    },
                    onMode = { mode = it.name },
                    onInterval = { interval = it; customInterval = "" },
                    onCustomInterval = { customInterval = it },
                    onMaxShare = { maxShare = it },
                    onStart = { requestStart() }
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
                    busy = busy,
                    onRefresh = {
                        busy = true
                        scope.launch {
                            runCatching { repository.refreshTrips() }.onSuccess { tripRows = it }
                                .onFailure { showMessage(it.message ?: "历史行程加载失败") }
                            busy = false
                        }
                    },
                    onOpen = { trip -> active = trip; page = AppPage.ACTIVE.name }
                )
                AppPage.SETTINGS.name -> SettingsPage(
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
                        context.getSharedPreferences("trip-share-settings", Context.MODE_PRIVATE).edit()
                            .putInt("interval", chosen).putString("mode", selectedMode.name).putInt("max_share", selectedMax).apply()
                        interval = chosen
                        mode = selectedMode.name
                        maxShare = selectedMax
                        val targetId = settingsTripId
                        if (targetId == null) {
                            page = AppPage.HOME.name
                            showMessage("默认设置已保存")
                        } else {
                            busy = true
                            scope.launch {
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
                else -> HomePage(active, {}, {}, {})
            }
            if (busy && page != AppPage.CREATE.name) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (showConsent) {
        val hasAndroidAmapKey = BuildConfig.AMAP_ANDROID_KEY.isNotBlank() && BuildConfig.AMAP_ANDROID_KEY != "CHANGE_ME"
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("位置与隐私说明") },
            text = {
                Text(
                    if (hasAndroidAmapKey)
                        "开始行程后，同行会在行程期间使用设备定位并同步位置，供家人通过链接查看；结束后会停止记录。若使用高德地图定位，高德会处理设备位置等必要信息，也可以只使用系统定位。"
                    else
                        "开始行程后，同行会在行程期间使用系统定位并同步位置，供家人通过链接查看；结束后会停止记录。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    LocationDisclosureStore.accept(context)
                    consentAccepted = true
                    if (hasAndroidAmapKey) {
                        AmapConsentStore.accept(context)
                        amapConsentAccepted = true
                    }
                    showConsent = false
                }) { Text(if (hasAndroidAmapKey) "同意并继续" else "我知道了并继续") }
            },
            dismissButton = {
                if (hasAndroidAmapKey && !amapConsentAccepted) {
                    TextButton(onClick = {
                        LocationDisclosureStore.accept(context)
                        consentAccepted = true
                        showConsent = false
                    }) { Text("仅使用系统定位") }
                } else TextButton(onClick = { showConsent = false }) { Text("稍后") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        )
    }

    searchTarget?.let { target ->
        PlaceSearchDialog(
            repository = repository,
            onDismiss = { searchTarget = null },
            onSelect = { place ->
                if (target == "origin") {
                    origin = place.asMarker()
                    originManuallyChanged = true
                    originLocationFailed = false
                } else destination = place.asMarker()
                searchTarget = null
            }
        )
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
                            busy = true
                            scope.launch {
                                val synced = repository.endTrip(trip.id)
                                TripSyncWorker.enqueue(context, trip.id)
                                context.stopService(android.content.Intent(context, TripLocationService::class.java))
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

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("同行") },
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
    active: LocalTripSummary?,
    onCreate: () -> Unit,
    onOpenActive: (LocalTripSummary) -> Unit,
    onHistory: () -> Unit
) {
    val currentTrip = active?.takeIf { it.isActive }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(currentTrip?.id) {
        if (currentTrip != null) {
            while (true) {
                delay(30_000)
                now = System.currentTimeMillis()
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp)) {
        Spacer(Modifier.weight(0.75f))
        if (currentTrip == null) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("开始一次位置共享", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "上车后开始，家人打开链接即可看到你的位置。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val currentState = tripUiState(currentTrip, currentTrip.latestPositionAt, now)
                TripStatus(currentState, currentTrip.latestPositionAt, now)
                TripTitle(currentTrip, now)
                Text("行程正在共享", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1.25f))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = if (currentTrip == null) "开始共享" else "查看共享中",
                onClick = { currentTrip?.let(onOpenActive) ?: onCreate() },
                enabled = true
            )
            TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
                Text("查看历史行程", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CreateTripPage(
    origin: PlaceMarker?, originLocationPlaceholder: String, destination: PlaceMarker?, mode: TrackingMode, interval: Int, customInterval: String,
    maxShare: Int, busy: Boolean, error: String?, onOrigin: () -> Unit, onDestination: () -> Unit, onRelocate: () -> Unit,
    onMode: (TrackingMode) -> Unit, onInterval: (Int) -> Unit, onCustomInterval: (String) -> Unit,
    onMaxShare: (Int) -> Unit, onStart: () -> Unit
) {
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            LocationField(
                title = "出发地",
                value = origin?.name,
                placeholder = originLocationPlaceholder,
                onClick = onOrigin,
                trailingIcon = Icons.Default.MyLocation,
                onTrailingClick = onRelocate
            )
            LocationField(
                title = "目的地（选填）",
                value = destination?.name,
                placeholder = "可留空",
                onClick = onDestination
            )
            Text("目的地可留空，开始后将自动记录位置并分享。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { showAdvanced = true },
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("高级设置", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            PrimaryButton(text = if (busy) "正在开始…" else "开始共享", onClick = onStart, enabled = !busy)
        }
    }
    if (showAdvanced) {
        AdvancedSettingsSheet(
            mode = mode,
            interval = interval,
            customInterval = customInterval,
            maxShare = maxShare,
            onMode = onMode,
            onInterval = onInterval,
            onCustomInterval = onCustomInterval,
            onMaxShare = onMaxShare,
            onDismiss = { showAdvanced = false }
        )
    }
}

@Composable
private fun SettingsFields(
    mode: TrackingMode, interval: Int, customInterval: String, maxShare: Int,
    onMode: (TrackingMode) -> Unit, onInterval: (Int) -> Unit, onCustomInterval: (String) -> Unit,
    onMaxShare: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("位置记录模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ChoiceRow(
            selected = mode == TrackingMode.STANDARD,
            title = "标准模式",
            subtitle = "按照设定间隔记录位置，较省电。",
            onClick = { onMode(TrackingMode.STANDARD) }
        )
        ChoiceRow(
            selected = mode == TrackingMode.DETAILED,
            title = "详细轨迹",
            subtitle = "更频繁记录轨迹，耗电更高。",
            onClick = { onMode(TrackingMode.DETAILED) }
        )
        Text("位置更新间隔", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60, 300, 600, 900).forEach { seconds ->
                val label = when (seconds) { 60 -> "1 分"; 300 -> "5 分"; 600 -> "10 分"; else -> "15 分" }
                IntervalChoice(label, interval == seconds && customInterval.isBlank()) { onInterval(seconds) }
            }
        }
        OutlinedTextField(
            value = customInterval,
            onValueChange = { text -> onCustomInterval(text.filter(Char::isDigit).take(2)) },
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
                ) { onMaxShare(seconds) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedSettingsSheet(
    mode: TrackingMode, interval: Int, customInterval: String, maxShare: Int,
    onMode: (TrackingMode) -> Unit, onInterval: (Int) -> Unit, onCustomInterval: (String) -> Unit,
    onMaxShare: (Int) -> Unit, onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outlineVariant) }
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).imePadding()
                .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("高级设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            SettingsFields(mode, interval, customInterval, maxShare, onMode, onInterval, onCustomInterval, onMaxShare)
            Spacer(Modifier.height(12.dp))
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
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TripStatus(uiState, latestAt, now)
                TripTitle(trip, now)
            }
            TripMap(shareUrl = shareUrl, tripEnded = !trip.isActive)
            TripSummary(
                state = uiState,
                latestAt = latestAt,
                point = latest,
                pendingCount = pending,
                rejectedCount = rejected
            )
            if (uiState == TripUiState.STALE) StaleLocationIndicator()
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("家人查看链接", fontWeight = FontWeight.Medium)
                Text(
                    if (shareUrl != null) "家人可以通过链接查看你的行程" else if (trip.isShareExpired) "分享链接已过期，行程记录仍保留。" else "分享链接已撤销，行程记录仍保留。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!trip.isActive) {
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
private fun TripSummary(
    state: TripUiState, latestAt: String?, point: PositionEntity?, pendingCount: Int, rejectedCount: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val updated = latestAt?.let(::formatClock) ?: "尚未收到位置"
        Text(
            when (state) {
                TripUiState.ACTIVE -> "最近更新于 $updated · 正常共享中"
                TripUiState.STALE -> "最近更新于 $updated · 暂未更新"
                TripUiState.ENDED -> "最近更新于 $updated · 已结束"
            },
            fontWeight = FontWeight.Medium
        )
        if (state == TripUiState.ACTIVE) {
            point?.takeIf(::hasReliableSpeed)?.let { Text("时速约 ${((it.speedMps ?: 0.0) * 3.6).roundToLong()} km/h", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        when {
            pendingCount > 0 -> Text("$pendingCount 个位置等待同步", color = MaterialTheme.colorScheme.onSurfaceVariant)
            rejectedCount > 0 -> Text("$rejectedCount 个位置未能同步", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StaleLocationIndicator() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
        Spacer(Modifier.width(10.dp))
        Text("当前位置可能不是实时位置", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryPage(
    trips: List<LocalTripSummary>, busy: Boolean, onRefresh: () -> Unit,
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
            listOf("ALL" to "全部", "ACTIVE" to "进行中", "ENDED" to "已结束").forEach { (key, label) ->
                val selected = filter == key
                Surface(
                    modifier = Modifier.weight(1f).clip(CircleShape).clickable { filter = key },
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(label, modifier = Modifier.padding(vertical = 11.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${visibleTrips.size} 段行程", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Default.Refresh, "刷新历史", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
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
private fun SettingsPage(current: LocalTripSummary?, busy: Boolean, onSave: (TrackingMode, Int, Int) -> Unit) {
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
            SettingsFields(mode, interval, custom, maxShare, { mode = it }, { interval = it; custom = "" }, { custom = it }, { maxShare = it })
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

@Composable
private fun HomeOverflowMenu(onSettings: () -> Unit, onAbout: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("高级设置") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { expanded = false; onSettings() })
            DropdownMenuItem(text = { Text("关于同行") }, onClick = { expanded = false; onAbout() })
        }
    }
}

@Composable
private fun TripOverflowMenu(
    trip: LocalTripSummary,
    onSettings: () -> Unit,
    onResume: () -> Unit,
    onRevoke: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多行程操作") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (trip.isActive) {
                DropdownMenuItem(text = { Text("继续后台记录") }, leadingIcon = { Icon(Icons.Default.MyLocation, null) }, onClick = { expanded = false; onResume() })
                DropdownMenuItem(text = { Text("行程设置") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { expanded = false; onSettings() })
                if (trip.canShare) DropdownMenuItem(text = { Text("撤销查看链接") }, leadingIcon = { Icon(Icons.Default.Link, null) }, onClick = { expanded = false; onRevoke() })
            } else {
                DropdownMenuItem(text = { Text("删除行程") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { expanded = false; onDelete() })
            }
            DropdownMenuItem(text = { Text("历史行程") }, leadingIcon = { Icon(Icons.Default.History, null) }, onClick = { expanded = false; onHistory() })
        }
    }
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
private fun LocationField(
    title: String,
    value: String?,
    placeholder: String,
    onClick: () -> Unit,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onTrailingClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(23.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    value ?: placeholder,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                if (trailingIcon != null && onTrailingClick != null) {
                    IconButton(onClick = onTrailingClick, modifier = Modifier.size(44.dp)) {
                        Icon(trailingIcon, contentDescription = "重新定位", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
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
                if (state == TripUiState.ACTIVE) androidx.compose.ui.graphics.Color(0xFF82978C) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(label + detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TripTitle(trip: LocalTripSummary, now: Long = System.currentTimeMillis()) {
    val route = when {
        trip.origin != null && trip.destination != null -> "${trip.origin.name} → ${trip.destination.name}"
        trip.origin != null -> "从${trip.origin.name}出发"
        else -> trip.title.ifBlank { "我的行程" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(route, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text("已持续 ${formatDuration(trip.startedAt, trip.endedAt, now)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TripHistoryItem(trip: LocalTripSummary, onClick: () -> Unit) {
    val state = if (trip.isActive) "共享中" else "已结束"
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
                    "${formatHistoryTimestamp(trip.startedAt)} · ${formatDuration(trip.startedAt, trip.endedAt, System.currentTimeMillis())}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(state, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
                        else Icon(Icons.Default.Refresh, "搜索")
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

private fun pageTitle(page: String, settingsTripId: String?, active: LocalTripSummary?): String = when (page) {
    AppPage.HOME.name -> "同行"
    AppPage.CREATE.name -> "创建行程"
    AppPage.ACTIVE.name -> if (active?.isActive == true) "共享中" else "已结束"
    AppPage.HISTORY.name -> "历史行程"
    AppPage.SETTINGS.name -> if (settingsTripId == null) "高级设置" else "行程设置"
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

private fun formatClock(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("--:--")

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

private fun intervalLabel(seconds: Int): String = if (seconds % 60 == 0) "${seconds / 60} 分钟" else "$seconds 秒"

private fun maxShareLabel(seconds: Int): String = when (seconds) {
    3_600 -> "1 小时"
    21_600 -> "6 小时"
    43_200 -> "12 小时"
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
