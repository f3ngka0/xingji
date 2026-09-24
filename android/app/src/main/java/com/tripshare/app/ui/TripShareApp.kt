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
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.work.WorkManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

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
    var locationAttempted by rememberSaveable { mutableStateOf(false) }
    var searchTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var showEndConfirm by rememberSaveable { mutableStateOf(false) }
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
        busy = true
        try {
            val sample = withTimeout(45_000L) { AmapLocationSampler(context).capture(needAddress = true) }
                ?: throw IllegalStateException("暂时无法获取有效位置，请重试或手动搜索出发地")
            if (!allowReplace && originManuallyChanged) return
            val label = sample.placeLabel?.let { " · $it" } ?: ""
            origin = PlaceMarker("当前位置$label", sample.latWgs84, sample.lonWgs84)
            if (allowReplace) originManuallyChanged = false
        } catch (error: Exception) {
            showMessage(error.message ?: "定位失败，请检查权限和高德 Android Key")
        } finally {
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
            showMessage("行程已开始，位置先保存在本机并开始同步")
        } catch (error: Exception) {
            created?.let { trip ->
                repository.endTrip(trip.id)
                TripSyncWorker.enqueue(context, trip.id)
            }
            showMessage(error.message ?: "行程创建失败，请检查网络和服务器配置")
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

    LaunchedEffect(page, consentAccepted, locationAttempted) {
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
                title = { Text(pageTitle(page, settingsTripId), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (page != AppPage.HOME.name) IconButton(onClick = {
                        if (page == AppPage.SETTINGS.name && settingsTripId != null) page = AppPage.ACTIVE.name
                        else page = AppPage.HOME.name
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
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
                            origin = null
                            originManuallyChanged = false
                            destination = null
                            locationAttempted = false
                            page = AppPage.CREATE.name
                            if (!consentAccepted) showConsent = true
                        }
                    },
                    onResume = { trip ->
                        active = trip
                        if (!consentAccepted) showConsent = true else requestLocation(PermissionPurpose.RESUME)
                    },
                    onOpenActive = { active = it; page = AppPage.ACTIVE.name },
                    onHistory = {
                        page = AppPage.HISTORY.name
                        scope.launch { runCatching { repository.refreshTrips() }.onSuccess { tripRows = it } }
                    },
                    onSettings = { settingsTripId = null; page = AppPage.SETTINGS.name }
                )
                AppPage.CREATE.name -> CreateTripPage(
                    origin = origin,
                    destination = destination,
                    mode = TrackingMode.valueOf(mode),
                    interval = interval,
                    customInterval = customInterval,
                    maxShare = maxShare,
                    busy = busy,
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
                        onSettings = { settingsTripId = trip.id; page = AppPage.SETTINGS.name },
                        onEnd = { showEndConfirm = true },
                        onRevoke = {
                            scope.launch {
                                busy = true
                                runCatching { repository.revokeShare(trip.id) }
                                    .onSuccess { active = repository.localTrip(trip.id); showMessage("分享链接已撤销") }
                                    .onFailure { showMessage(it.message ?: "撤销失败，请检查网络") }
                                busy = false
                            }
                        },
                        onCopy = { link -> copyText(context, "行程分享链接", link); showMessage("分享链接已复制") },
                        onResume = { requestLocation(PermissionPurpose.RESUME) },
                        onHistory = { page = AppPage.HISTORY.name }
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
                    onOpen = { trip -> active = trip; page = AppPage.ACTIVE.name },
                    onDelete = { deleteCandidate = it }
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
                else -> HomePage(active, {}, {}, {}, {}, {})
            }
            if (busy) {
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
                        "行程开始后，位置会先保存在本机，再上传到你配置的服务器；只有你主动开始行程时才会进行后台采集，结束行程后停止。若你选择高德定位，高德 SDK 会处理设备位置、网络及必要的设备信息；你也可以只使用 Android 系统定位。你可以随时撤销分享链接或删除行程。"
                    else
                        "当前未配置高德 Android 定位 SDK Key，位置将由 Android 系统定位服务采集。行程开始后，位置会先保存在本机，再上传到你配置的服务器；只有你主动开始行程时才会进行后台采集，结束行程后停止。你可以随时撤销分享链接或删除行程。"
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
                }) { Text(if (hasAndroidAmapKey) "同意并使用高德定位" else "我知道了并继续") }
            },
            dismissButton = {
                if (hasAndroidAmapKey && !amapConsentAccepted) {
                    TextButton(onClick = {
                        LocationDisclosureStore.accept(context)
                        consentAccepted = true
                        showConsent = false
                    }) { Text("仅使用系统定位") }
                } else TextButton(onClick = { showConsent = false }) { Text("稍后") }
            }
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
                } else destination = place.asMarker()
                searchTarget = null
            }
        )
    }

    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text("结束本次行程？") },
            text = { Text("结束后会停止采集新位置。已记录的位置会保留，未上传的位置会在网络恢复后继续同步。") },
            confirmButton = {
                Button(onClick = {
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
                            showMessage(if (synced) "行程已结束" else "行程已在本机结束，服务器状态将在网络恢复后更新")
                        }
                    }
                }) { Text("结束行程") }
            },
            dismissButton = { TextButton(onClick = { showEndConfirm = false }) { Text("继续记录") } }
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
                                if (active?.id == candidate.id) active = null
                                showMessage("行程记录已删除")
                            }.onFailure { showMessage(it.message ?: "删除失败，请检查网络") }
                        busy = false
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun HomePage(
    active: LocalTripSummary?,
    onCreate: () -> Unit,
    onResume: (LocalTripSummary) -> Unit,
    onOpenActive: (LocalTripSummary) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("把旅途位置，安心分享给家人。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("按间隔记录真实位置，不规划路线。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        active?.takeIf { it.isActive }?.let { trip ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ShareLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("有一段行程尚未结束", fontWeight = FontWeight.SemiBold)
                    }
                    Text(trip.title, style = MaterialTheme.typography.titleMedium)
                    Text("上次开始于 ${formatDateTime(trip.startedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onResume(trip) }, modifier = Modifier.weight(1f)) { Text("继续记录") }
                        OutlinedButton(onClick = { onOpenActive(trip) }, modifier = Modifier.weight(1f)) { Text("查看行程") }
                    }
                }
            }
        }
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().height(58.dp), enabled = active?.isActive != true) {
            Icon(Icons.Default.AddLocationAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("创建位置共享行程")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickAction(Icons.Default.History, "行程历史", onHistory, Modifier.weight(1f))
            QuickAction(Icons.Default.Settings, "记录设置", onSettings, Modifier.weight(1f))
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("只有你主动开始后，应用才会在后台记录位置。行程到期或你手动结束时，采集会停止。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CreateTripPage(
    origin: PlaceMarker?, destination: PlaceMarker?, mode: TrackingMode, interval: Int, customInterval: String,
    maxShare: Int, busy: Boolean, onOrigin: () -> Unit, onDestination: () -> Unit, onRelocate: () -> Unit,
    onMode: (TrackingMode) -> Unit, onInterval: (Int) -> Unit, onCustomInterval: (String) -> Unit,
    onMaxShare: (Int) -> Unit, onStart: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("先确认行程信息，目的地可以留空。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("出发地", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = origin?.name ?: "",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("自动获取当前位置") },
                    leadingIcon = { Icon(Icons.Default.LocationOn, null) },
                    shape = RoundedCornerShape(14.dp)
                )
                IconButton(onClick = onRelocate, enabled = !busy) { Icon(Icons.Default.MyLocation, "重新定位") }
            }
            TextButton(onClick = onOrigin) { Text("搜索或修改出发地") }
            if (origin != null) Text("起点标记不代替设备实际采集的第一个位置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("目的地", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = destination?.name ?: "",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDestination),
                placeholder = { Text("目的地（选填）") },
                leadingIcon = { Icon(Icons.Default.LocationOn, null) },
                trailingIcon = { TextButton(onClick = onDestination) { Text("搜索") } },
                shape = RoundedCornerShape(14.dp)
            )
            if (destination != null) Text("仅作为地图标记，不用于路线规划。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsFields(
            mode = mode,
            interval = interval,
            customInterval = customInterval,
            maxShare = maxShare,
            onMode = onMode,
            onInterval = onInterval,
            onCustomInterval = onCustomInterval,
            onMaxShare = onMaxShare
        )
        Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (busy) "正在准备行程…" else "开始行程")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsFields(
    mode: TrackingMode, interval: Int, customInterval: String, maxShare: Int,
    onMode: (TrackingMode) -> Unit, onInterval: (Int) -> Unit, onCustomInterval: (String) -> Unit,
    onMaxShare: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("轨迹记录模式", fontWeight = FontWeight.SemiBold)
        ChoiceRow(
            selected = mode == TrackingMode.STANDARD,
            title = "标准模式",
            subtitle = "按所选间隔采集并同步，较省电",
            onClick = { onMode(TrackingMode.STANDARD) }
        )
        ChoiceRow(
            selected = mode == TrackingMode.DETAILED,
            title = "详细轨迹",
            subtitle = "每分钟采集，按所选间隔批量同步，耗电较多",
            onClick = { onMode(TrackingMode.DETAILED) }
        )
        Text(if (mode == TrackingMode.DETAILED) "上传间隔" else "位置更新间隔", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60, 300, 600, 900).forEach { seconds ->
                val label = when (seconds) { 60 -> "1 分"; 300 -> "5 分"; 600 -> "10 分"; else -> "15 分" }
                IntervalChoice(label, interval == seconds && customInterval.isBlank()) { onInterval(seconds) }
            }
        }
        OutlinedTextField(
            value = customInterval,
            onValueChange = { text -> onCustomInterval(text.filter(Char::isDigit).take(2)) },
            label = { Text("自定义间隔（分钟，1–60）") },
            placeholder = { Text("默认 5 分钟") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )
        Text("最长共享时长", fontWeight = FontWeight.SemiBold)
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

@Composable
private fun ChoiceRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.Route, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun IntervalChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ActiveTripPage(
    trip: LocalTripSummary, repository: TripRepository, onSettings: () -> Unit, onEnd: () -> Unit,
    onRevoke: () -> Unit, onCopy: (String) -> Unit, onResume: () -> Unit, onHistory: () -> Unit
) {
    val context = LocalContext.current
    val localPoints by repository.observePoints(trip.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val pending by repository.observePendingCount(trip.id).collectAsStateWithLifecycle(initialValue = 0)
    val rejected by repository.observeRejectedCount(trip.id).collectAsStateWithLifecycle(initialValue = 0)
    var showLocalPoints by rememberSaveable(trip.id) { mutableStateOf(false) }
    var showMap by rememberSaveable(trip.id) { mutableStateOf(true) }
    val isActive = trip.isActive
    val latest = localPoints.lastOrNull()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isActive) "行程进行中" else "行程已结束", fontWeight = FontWeight.SemiBold)
                }
                Text(trip.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (trip.origin != null && trip.destination != null) Text("${trip.origin.name} → ${trip.destination.name}")
                else if (trip.origin != null) Text("从${trip.origin.name}出发")
                Text("开始于 ${formatDateTime(trip.startedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                InfoLine(Icons.Default.AccessTime, "最近定位", latest?.capturedAt?.let(::formatDateTime) ?: trip.latestPositionAt?.let(::formatDateTime) ?: "尚无位置")
                InfoLine(Icons.Default.SignalCellularAlt, "同步状态", if (pending == 0 && rejected == 0) "已同步" else "$pending 个待同步 · $rejected 个被服务器拒绝")
                if (latest?.speedMps != null) InfoLine(Icons.Default.Route, "采样时速度", "${(latest.speedMps * 3.6).roundToLong()} km/h")
                val eta = latest?.let { roughEta(it, trip.destination) }
                if (eta != null) InfoLine(Icons.Default.AccessTime, "到达时间估算", eta)
                if (trip.destination != null) Text("到达时间为直线距离与当前采样速度的粗略估算，不代表实际路线或行程承诺。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val shareUrl = trip.shareUrl?.takeIf { trip.canShare }
        if (shareUrl != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("家人查看链接", fontWeight = FontWeight.SemiBold)
                    }
                    Text(shareUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onCopy(shareUrl) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(7.dp)); Text("复制链接")
                        }
                        OutlinedButton(onClick = onRevoke, modifier = Modifier.weight(1f)) { Text("撤销链接") }
                    }
                }
            }
        } else if (trip.shareRevokedAt != null || trip.isShareExpired) {
            InfoBanner(if (trip.isShareExpired) "分享链接已过期，无法再通过链接查看。行程历史仍保留。" else "分享链接已撤销。行程历史仍保留。")
        }

        if (shareUrl != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("家人视角地图", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { showMap = !showMap }) { Text(if (showMap) "收起" else "查看") }
            }
            if (showMap) {
                Card(shape = RoundedCornerShape(18.dp)) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        factory = { viewContext -> WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.setSupportZoom(false)
                            webChromeClient = WebChromeClient()
                            webViewClient = WebViewClient()
                            loadUrl(shareUrl)
                        } },
                        update = { view -> if (view.url != shareUrl) view.loadUrl(shareUrl) }
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Route, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("本机记录", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${localPoints.size} 点", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("未同步位置仅保存在本机，网络恢复后会自动补传。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showLocalPoints = !showLocalPoints }, enabled = localPoints.any { it.syncState != "synced" }) {
                    Text(if (showLocalPoints) "收起未同步位置" else "查看未同步位置（${localPoints.count { it.syncState != "synced" }}）")
                }
                if (showLocalPoints) {
                    localPoints.filter { it.syncState != "synced" }.takeLast(30).asReversed().forEach { LocalPointRow(it) }
                }
            }
        }

        if (isActive) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Settings, null); Spacer(Modifier.width(6.dp)); Text("行程设置")
                }
                Button(onClick = onEnd, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.StopCircle, null); Spacer(Modifier.width(6.dp)); Text("结束行程")
                }
            }
            OutlinedButton(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("继续后台记录") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f)) { Text("行程历史") }
                Button(onClick = onSettings, modifier = Modifier.weight(1f)) { Text("查看设置") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.8f))
        Text(value, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.2f))
    }
}

@Composable
private fun LocalPointRow(point: PositionEntity) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDateTime(point.capturedAt), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(if (point.syncState == "rejected") "上传失败" else "待同步", color = if (point.syncState == "rejected") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        Text("${point.lat}, ${point.lon} · 精度 ${point.accuracyM.roundToLong()} 米", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        point.syncError?.let { Text("服务器响应：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider(Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun HistoryPage(
    trips: List<LocalTripSummary>, busy: Boolean, onRefresh: () -> Unit,
    onOpen: (LocalTripSummary) -> Unit, onDelete: (LocalTripSummary) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已保存 ${trips.size} 段行程", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Default.Refresh, "刷新历史") }
        }
        if (trips.isEmpty()) EmptyState("还没有保存的行程")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            items(trips, key = { it.id }) { trip ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().clickable { onOpen(trip) }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(trip.title, fontWeight = FontWeight.SemiBold)
                            Text(if (trip.isActive) "进行中" else "已结束 · ${formatDateTime(trip.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onDelete(trip) }) { Icon(Icons.Default.DeleteOutline, "删除行程", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(if (current == null) "设置新行程的默认记录方式。" else "修改当前行程的采集与上传频率。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsFields(mode, interval, custom, maxShare, { mode = it }, { interval = it; custom = "" }, { custom = it }, { maxShare = it })
        Button(
            onClick = { onSave(mode, custom.toIntOrNull()?.coerceIn(1, 60)?.times(60) ?: interval, maxShare) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text(if (busy) "正在保存…" else "保存设置") }
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
                        singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)
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
                LazyColumn(Modifier.height(280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(results) { place ->
                        Column(Modifier.fillMaxWidth().clickable { onSelect(place) }.padding(vertical = 9.dp)) {
                            Text(place.name, fontWeight = FontWeight.Medium)
                            place.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            HorizontalDivider(Modifier.padding(top = 7.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoBanner(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(text, Modifier.padding(15.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun pageTitle(page: String, settingsTripId: String?): String = when (page) {
    AppPage.HOME.name -> "行程共享"
    AppPage.CREATE.name -> "创建行程"
    AppPage.ACTIVE.name -> "行程详情"
    AppPage.HISTORY.name -> "行程历史"
    AppPage.SETTINGS.name -> if (settingsTripId == null) "记录设置" else "行程设置"
    else -> "行程共享"
}

private fun formatDateTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

private fun copyText(context: Context, label: String, value: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
}

private suspend fun latestLocalTime(repository: TripRepository, tripId: String): String {
    return withContext(Dispatchers.IO) { repository.latestPoint(tripId)?.capturedAt ?: Instant.now().toString() }
}

private fun roughEta(point: PositionEntity, destination: PlaceMarker?): String? {
    if (destination == null) return null
    val speed = point.speedMps?.takeIf { it > 1.0 && it.isFinite() } ?: return null
    val distance = haversineMeters(point.lat, point.lon, destination.lat, destination.lon)
    val seconds = (distance / speed).roundToLong().coerceAtLeast(0)
    val duration = Duration.ofSeconds(seconds)
    val text = when {
        duration.toHours() > 0 -> "${duration.toHours()} 小时 ${duration.toMinutesPart()} 分"
        duration.toMinutes() > 0 -> "${duration.toMinutes()} 分钟"
        else -> "少于 1 分钟"
    }
    return "直线粗略估算约 $text"
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val radius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
    return radius * 2 * atan2(sqrt(a), sqrt(1 - a))
}
