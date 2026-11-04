package com.microhorse.zaishucamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val PREFS_NAME = "zaishu_prefs"
private const val KEY_FOLDERS = "folder_list"
private const val KEY_SELECTED_FOLDER = "selected_folder"
private const val KEY_AUTO_GOOGLE_PHOTOS_BACKUP = "auto_google_photos_backup"

class MainActivity : ComponentActivity() {

    private val volumeShutterFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    var isCameraPreviewActive: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    ZaishuCameraApp(
                        volumeShutterEvents = volumeShutterFlow,
                        onPreviewStateChanged = { isCameraPreviewActive = it }
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (isCameraPreviewActive) {
                if (event?.repeatCount == 0) {
                    volumeShutterFlow.tryEmit(Unit)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

/**
 * 単一画面のUI状態
 */
private sealed interface CaptureUiState {
    data object CameraPreview : CaptureUiState
    data class MemoInput(
        val capturedBitmap: Bitmap,
        val capturedTimeMillis: Long,
        val capturedLocation: Location?
    ) : CaptureUiState
}

private fun hasLocationPermissionGranted(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

@SuppressLint("MissingPermission")
private fun getBestLastKnownLocation(context: Context): Location? {
    if (!hasLocationPermissionGranted(context)) return null
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
        "fused"
    )
    var bestLocation: Location? = null
    for (provider in providers) {
        val loc = try {
            locationManager.getLastKnownLocation(provider)
        } catch (_: Exception) {
            null
        } ?: continue
        if (bestLocation == null || loc.time > bestLocation.time) {
            bestLocation = loc
        }
    }
    return bestLocation
}

@Composable
fun ZaishuCameraApp(
    volumeShutterEvents: SharedFlow<Unit>,
    onPreviewStateChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val trialAndBillingManager = remember { TrialAndBillingManager(context) }
    val licenseState by trialAndBillingManager.uiState.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            trialAndBillingManager.release()
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasLocationPermission by remember {
        mutableStateOf(hasLocationPermissionGranted(context))
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCameraPermission = result[Manifest.permission.CAMERA]
            ?: (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED)
        hasLocationPermission = hasLocationPermissionGranted(context)
        trialAndBillingManager.refreshTrialState()
    }

    LaunchedEffect(Unit) {
        trialAndBillingManager.refreshTrialState()
        if (!hasCameraPermission || !hasLocationPermission) {
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    if (!licenseState.canUseApp) {
        LaunchedEffect(Unit) {
            onPreviewStateChanged(false)
        }
        ProPaywallScreen(
            licenseState = licenseState,
            onPurchaseClick = {
                if (activity != null) {
                    trialAndBillingManager.launchPurchaseFlow(activity)
                }
            },
            onRestoreClick = {
                trialAndBillingManager.restorePurchases()
            },
            onSecretUnlockSubmit = { code ->
                val unlocked = trialAndBillingManager.trySecretUnlock(code)
                if (unlocked) {
                    Toast.makeText(
                        context,
                        "✨ Pro永久ライセンス（一生の相棒）が解放されました！",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "コードが正しくありません",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                unlocked
            }
        )
    } else if (!hasCameraPermission) {
        LaunchedEffect(Unit) {
            onPreviewStateChanged(false)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    permissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            ) {
                Text(
                    text = "カメラ権限を許可する",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    } else {
        ZaishuMainScreen(
            hasLocationPermission = hasLocationPermission,
            trialAndBillingManager = trialAndBillingManager,
            volumeShutterEvents = volumeShutterEvents,
            onPreviewStateChanged = onPreviewStateChanged
        )
    }
}

@SuppressLint("ClickableViewAccessibility", "MissingPermission")
@Composable
private fun ZaishuMainScreen(
    hasLocationPermission: Boolean,
    trialAndBillingManager: TrialAndBillingManager,
    volumeShutterEvents: SharedFlow<Unit>,
    onPreviewStateChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val todayFolder = remember {
        SimpleDateFormat("yyyyMMdd_材集", Locale.getDefault()).format(Date())
    }
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // フォルダ（案件・場所タグ）一覧の読み込み
    val folders = remember {
        val savedRaw = prefs.getString(KEY_FOLDERS, null)
        val initialList = if (!savedRaw.isNullOrBlank()) {
            savedRaw.split("|").filter { it.isNotBlank() }.toMutableList()
        } else {
            mutableListOf(todayFolder, "標準")
        }
        if (!initialList.contains(todayFolder)) {
            initialList.add(0, todayFolder)
        }
        mutableStateListOf(*initialList.toTypedArray())
    }

    var selectedFolder by remember {
        val savedSelected = prefs.getString(KEY_SELECTED_FOLDER, todayFolder) ?: todayFolder
        mutableStateOf(if (folders.contains(savedSelected)) savedSelected else todayFolder)
    }

    // Googleフォト即時自動バックアップ連動（DCIM/Cameraへの同時保存）
    var autoGooglePhotosBackup by remember {
        mutableStateOf(prefs.getBoolean(KEY_AUTO_GOOGLE_PHOTOS_BACKUP, true))
    }

    // 現在選択中のフォルダ内の撮影済み枚数とURIリスト
    var folderPhotoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(selectedFolder, refreshTrigger) {
        withContext(Dispatchers.IO) {
            val uris = ImageSynthesizer.getFolderImageUris(appContext, selectedFolder)
            withContext(Dispatchers.Main) {
                folderPhotoUris = uris
            }
        }
    }

    fun saveFoldersToPrefs() {
        prefs.edit()
            .putString(KEY_FOLDERS, folders.joinToString("|"))
            .putString(KEY_SELECTED_FOLDER, selectedFolder)
            .apply()
    }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderNameInput by remember { mutableStateOf("") }

    var uiState by remember { mutableStateOf<CaptureUiState>(CaptureUiState.CameraPreview) }
    var memoText by remember { mutableStateOf("") }
    var isChecked by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var lastCapturedTime by remember { mutableLongStateOf(0L) }

    // GPS現在地（EXIF位置情報記録用）
    var currentLocation by remember { mutableStateOf<Location?>(getBestLastKnownLocation(context)) }

    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) {
            onDispose { }
        } else {
            currentLocation = getBestLastKnownLocation(context)
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val listener = LocationListener { loc ->
                currentLocation = loc
            }
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                "fused"
            )
            providers.forEach { provider ->
                try {
                    if (locationManager?.isProviderEnabled(provider) == true) {
                        locationManager.requestLocationUpdates(
                            provider,
                            2000L,
                            1f,
                            listener,
                            Looper.getMainLooper()
                        )
                    }
                } catch (_: Exception) {
                }
            }
            onDispose {
                try {
                    locationManager?.removeUpdates(listener)
                } catch (_: Exception) {
                }
            }
        }
    }

    // ライト常時点灯（トーチ）モード状態
    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }

    // ズーム状態（.5 / 1x / 2 ワンタップ ＆ 2本指ピンチズーム連動）
    var currentZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var minZoomRatio by remember { mutableFloatStateOf(0.5f) }
    var maxZoomRatio by remember { mutableFloatStateOf(8.0f) }

    LaunchedEffect(uiState, showNewFolderDialog) {
        onPreviewStateChanged(uiState is CaptureUiState.CameraPreview && !showNewFolderDialog)
    }

    // カメラセンサーの最大領域（4:3 フル画角）を活かす PreviewView
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    // ピンチイン・ピンチアウトによるスムーズズーム ＆ タップフォーカス
    DisposableEffect(previewView, cameraInstance) {
        val cam = cameraInstance
        val scaleGestureDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val activeCam = cameraInstance ?: return false
                    val current = activeCam.cameraInfo.zoomState.value?.zoomRatio ?: currentZoomRatio
                    val nextZoom = (current * detector.scaleFactor).coerceIn(minZoomRatio, maxZoomRatio)
                    activeCam.cameraControl.setZoomRatio(nextZoom)
                    currentZoomRatio = nextZoom
                    return true
                }
            }
        )

        previewView.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress && event.action == MotionEvent.ACTION_UP) {
                val activeCam = cameraInstance
                if (activeCam != null) {
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point).build()
                    activeCam.cameraControl.startFocusAndMetering(action)
                }
                view.performClick()
            }
            true
        }

        val zoomLiveData = cam?.cameraInfo?.zoomState
        val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { state ->
            if (state != null) {
                currentZoomRatio = state.zoomRatio
                minZoomRatio = state.minZoomRatio
                maxZoomRatio = state.maxZoomRatio
            }
        }
        zoomLiveData?.observe(lifecycleOwner, observer)

        onDispose {
            zoomLiveData?.removeObserver(observer)
            previewView.setOnTouchListener(null)
        }
    }

    val imageCapture = remember {
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
            .build()
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraInstance?.cameraControl?.enableTorch(false)
            cameraExecutor.shutdown()
        }
    }

    fun setTargetZoom(target: Float) {
        val cam = cameraInstance ?: return
        val clamped = target.coerceIn(minZoomRatio, maxZoomRatio)
        cam.cameraControl.setZoomRatio(clamped)
        currentZoomRatio = clamped
    }

    fun executeCaptureNow() {
        trialAndBillingManager.refreshTrialState()
        if (!trialAndBillingManager.uiState.value.canUseApp) return
        if (isCapturing || uiState !is CaptureUiState.CameraPreview) return
        isCapturing = true
        previewView.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        )
        val now = System.currentTimeMillis()
        lastCapturedTime = now
        val locationAtCapture = currentLocation ?: getBestLastKnownLocation(context)

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rawBitmap = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()

                    val normalizedBitmap = ImageSynthesizer.normalizeRotation(
                        rawBitmap,
                        rotation
                    )

                    ContextCompat.getMainExecutor(context).execute {
                        memoText = ""
                        isCapturing = false
                        uiState = CaptureUiState.MemoInput(
                            capturedBitmap = normalizedBitmap,
                            capturedTimeMillis = lastCapturedTime,
                            capturedLocation = locationAtCapture
                        )
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    ContextCompat.getMainExecutor(context).execute {
                        isCapturing = false
                        Toast.makeText(
                            context,
                            "撮影失敗: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    // 音量ボタン（＋／ー）でのシャッター撮影連動
    LaunchedEffect(Unit) {
        volumeShutterEvents.collect {
            executeCaptureNow()
        }
    }

    // カメラのライフサイクルバインド（4:3 フル画角で起動時に1回のみバインドし常時スタンバイ）
    LaunchedEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val previewResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(previewResolutionSelector)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            try {
                cameraProvider.unbindAll()
                val cam = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                cameraInstance = cam
                cam.cameraInfo.zoomState.value?.let { zs ->
                    currentZoomRatio = zs.zoomRatio
                    minZoomRatio = zs.minZoomRatio
                    maxZoomRatio = zs.maxZoomRatio
                }
                if (isTorchOn && cam.cameraInfo.hasFlashUnit()) {
                    cam.cameraControl.enableTorch(true)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "カメラ初期化エラー: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // 新規フォルダ追加ダイアログ
    if (showNewFolderDialog) {
        val dialogFocusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("フォルダ（案件・場所名）を追加", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "指定した名前で端末フォルダ（DCIM/ZaishuMemo/〇〇）が作成され、画像下部にも印字されます。",
                        fontSize = 13.sp,
                        color = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newFolderNameInput,
                        onValueChange = { newFolderNameInput = it },
                        placeholder = { Text("例: 第一圃場、A区画") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(dialogFocusRequester)
                    )
                    LaunchedEffect(Unit) {
                        dialogFocusRequester.requestFocus()
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sanitized = ImageSynthesizer.sanitizeFolderName(newFolderNameInput)
                        if (sanitized.isNotEmpty()) {
                            if (!folders.contains(sanitized)) {
                                folders.add(0, sanitized)
                            }
                            selectedFolder = sanitized
                            saveFoldersToPrefs()
                        }
                        newFolderNameInput = ""
                        showNewFolderDialog = false
                    }
                ) {
                    Text("追加して選択")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 【状態1: カメラプレビュー状態（4:3 フル画角プレビュー枠 ＋ 上下分離バー）】
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. 上部：フォルダ（案件・場所）ワンタップ切替バー ＆ Googleフォト連携バー（石色×墨黒×千歳緑の100年デザイン配色）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161615))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                // 1段目：Googleフォト即時同期トグル ＆ 現フォルダのGoogleフォト一括アップロードボタン
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // DCIM/Camera 自動バックアップ連動トグル
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (autoGooglePhotosBackup) Color(0xFF234F2E) else Color(0xFF262624)
                            )
                            .clickable {
                                autoGooglePhotosBackup = !autoGooglePhotosBackup
                                prefs.edit()
                                    .putBoolean(KEY_AUTO_GOOGLE_PHOTOS_BACKUP, autoGooglePhotosBackup)
                                    .apply()
                                val msg = if (autoGooglePhotosBackup) {
                                    "Googleフォト自動バックアップ連動: ON (DCIM/Cameraにも同時保存)"
                                } else {
                                    "Googleフォト自動バックアップ連動: OFF (専用フォルダのみ保存)"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (autoGooglePhotosBackup) "☁️ Gフォト自動同期: ON" else "☁️ Gフォト自動同期: OFF",
                            color = Color(0xFFFFFFFF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 現在のフォルダの写真をGoogleフォトへまとめて送るボタン
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF262624))
                            .border(1.dp, Color(0xFF6E6B64), RoundedCornerShape(6.dp))
                            .clickable {
                                if (folderPhotoUris.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        "「$selectedFolder」フォルダにはまだ写真がありません",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    shareFolderToGooglePhotos(context, selectedFolder, folderPhotoUris)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📤 「$selectedFolder」(${folderPhotoUris.size}枚)をGフォトへ送る",
                            color = Color(0xFFFFFFFF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 2段目：フォルダ（案件・場所タグ）横スクロールチップ
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ＋ 新規フォルダ作成ボタン
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFF6E6B64), RoundedCornerShape(16.dp))
                            .background(Color(0xFF1C1C1A))
                            .clickable {
                                newFolderNameInput = ""
                                showNewFolderDialog = true
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "＋ フォルダ追加",
                            color = Color(0xFFFFFFFF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    folders.forEach { folder ->
                        val isSelected = folder == selectedFolder
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isSelected) Color(0xFFFFFFFF) else Color(0xFF262624)
                                )
                                .clickable {
                                    selectedFolder = folder
                                    saveFoldersToPrefs()
                                }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "📁 $folder",
                                color = if (isSelected) Color(0xFF1C1C1A) else Color(0xFFA6A39B),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // 2. 中央：カメラセンサーの4:3フル画角を100%表示するプレビュー枠（＋ズームピル）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 撮影前にチェックONにしている場合、プレビュー右上にも✓バッジを表示
                    if (isChecked) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(14.dp)
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF234F2E))
                                .border(2.5.dp, Color(0xFFFFFFFF), CircleShape)
                                .clickable { isChecked = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✓",
                                color = Color(0xFFFFFFFF),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    // プレビュー下部：標準カメラアプリ風 ワンタップズーム切替ピル（.5 / 1x / 2）
                    ZoomSelectorPill(
                        currentZoomRatio = currentZoomRatio,
                        minZoomRatio = minZoomRatio,
                        maxZoomRatio = maxZoomRatio,
                        onSelectZoom = { target -> setTargetZoom(target) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                    )
                }
            }

            // 3. 下部：左＝🔦ライト、中央＝シャッター、右＝✓チェック（仮決め）（石色×墨黒×千歳緑）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161615))
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                // 左側：ライト点灯モード（文字固定・色変化のみで枠サイズ不変）
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (isTorchOn) Color(0xFFFFFFFF) else Color(0xFF262624)
                        )
                        .border(
                            width = 1.5.dp,
                            color = if (isTorchOn) Color(0xFFFFFFFF) else Color(0xFF6E6B64),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .clickable {
                            val cam = cameraInstance
                            if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
                                Toast.makeText(
                                    context,
                                    "ライト非対応の端末です",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                val nextState = !isTorchOn
                                isTorchOn = nextState
                                cam.cameraControl.enableTorch(nextState)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🔦 ライト",
                        color = if (isTorchOn) Color(0xFF1C1C1A) else Color(0xFFFFFFFF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 中央：シャッターボタン（音量ボタンでも撮影可）
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(80.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color(0xFFFFFFFF), CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(if (isCapturing) Color(0xFF6E6B64) else Color(0xFFFFFFFF))
                        .clickable(
                            enabled = !isCapturing && uiState is CaptureUiState.CameraPreview
                        ) {
                            executeCaptureNow()
                        }
                )

                // 右側：撮影前でもワンタップで切り替えられる「✓ チェック（仮決め）」ボタン（文字固定で枠サイズ不変）
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (isChecked) Color(0xFF234F2E) else Color(0xFF262624)
                        )
                        .border(
                            width = 1.5.dp,
                            color = if (isChecked) Color(0xFFFFFFFF) else Color(0xFF6E6B64),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .clickable { isChecked = !isChecked }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓ チェック",
                        color = Color(0xFFFFFFFF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 【状態2: メモ即時入力状態（単一画面オーバーレイ）】
        val currentState = uiState
        if (currentState is CaptureUiState.MemoInput) {
            MemoInputOverlay(
                capturedBitmap = currentState.capturedBitmap,
                selectedFolder = selectedFolder,
                memoText = memoText,
                onMemoChange = { input ->
                    if (trialAndBillingManager.trySecretUnlock(input)) {
                        memoText = ""
                        Toast.makeText(
                            context,
                            "✨ Pro永久ライセンス（一生の相棒）が解放されました！",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        memoText = input
                    }
                },
                isChecked = isChecked,
                onCheckedChange = { isChecked = it },
                onDiscard = {
                    val bitmapToRecycle = currentState.capturedBitmap
                    memoText = ""
                    isChecked = false
                    uiState = CaptureUiState.CameraPreview
                    bitmapToRecycle.recycle()
                },
                onSaveAndNext = {
                    // 【状態3: バックグラウンド合成・即時復帰】
                    val bitmapToSave = currentState.capturedBitmap
                    val textToSave = if (trialAndBillingManager.trySecretUnlock(memoText)) {
                        Toast.makeText(
                            context,
                            "✨ Pro永久ライセンス（一生の相棒）が解放されました！",
                            Toast.LENGTH_LONG
                        ).show()
                        ""
                    } else {
                        memoText
                    }
                    val checkedToSave = isChecked
                    val folderToSave = selectedFolder
                    val autoBackup = autoGooglePhotosBackup
                    val timeToSave = currentState.capturedTimeMillis
                    val locationToSave = currentState.capturedLocation

                    // 1. UIを即座に【状態1: カメラプレビュー状態】へ戻し、入力欄・チェック状態もクリア
                    memoText = ""
                    isChecked = false
                    uiState = CaptureUiState.CameraPreview

                    // 2. 裏側（Dispatchers.IO）で画像合成とMediaStore保存を実行
                    coroutineScope.launch(Dispatchers.IO) {
                        val savedUri = ImageSynthesizer.synthesizeAndSave(
                            context = appContext,
                            originalBitmap = bitmapToSave,
                            memoText = textToSave,
                            folderName = folderToSave,
                            alsoSaveToCameraRoll = autoBackup,
                            capturedTimeMillis = timeToSave,
                            isChecked = checkedToSave,
                            location = locationToSave
                        )
                        withContext(Dispatchers.Main) {
                            trialAndBillingManager.refreshTrialState()
                            refreshTrigger++
                            val checkLabel = if (checkedToSave) " (✓チェック付)" else ""
                            val msg = if (savedUri != null) {
                                "📁「$folderToSave」に保存しました$checkLabel"
                            } else {
                                "保存に失敗しました"
                            }
                            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }
}

/**
 * 標準カメラアプリ風のズーム切替カプセル（0.5x / 1x / 2x ワンタップ切替 ＋ ピンチズーム時の倍率表示）
 */
@Composable
private fun ZoomSelectorPill(
    currentZoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    onSelectZoom: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val ultraWideTarget = if (minZoomRatio < 1.0f) max(0.5f, minZoomRatio) else 0.5f
    val twoXTarget = min(2.0f, maxZoomRatio)

    val isUltraWideSelected = currentZoomRatio < 0.85f
    val isOneXSelected = currentZoomRatio in 0.85f..1.49f
    val isTwoXSelected = currentZoomRatio >= 1.5f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ZoomButtonItem(
            label = if (isUltraWideSelected) formatZoomLabel(currentZoomRatio) else ".5",
            isSelected = isUltraWideSelected,
            onClick = { onSelectZoom(ultraWideTarget) }
        )

        ZoomButtonItem(
            label = if (isOneXSelected) formatZoomLabel(currentZoomRatio) else "1",
            isSelected = isOneXSelected,
            onClick = { onSelectZoom(1.0f) }
        )

        ZoomButtonItem(
            label = if (isTwoXSelected) formatZoomLabel(currentZoomRatio) else "2",
            isSelected = isTwoXSelected,
            onClick = { onSelectZoom(twoXTarget) }
        )
    }
}

private fun formatZoomLabel(zoom: Float): String {
    val rounded = (zoom * 10f).toInt() / 10f
    return if (abs(rounded - rounded.toInt()) < 0.05f) {
        "${rounded.toInt()}x"
    } else {
        String.format(Locale.US, "%.1fx", rounded)
    }
}

@Composable
private fun ZoomButtonItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(if (isSelected) 38.dp else 32.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) Color(0xFFFFFFFF) else Color(0x44FFFFFF)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color(0xFF1C1C1A) else Color(0xFFFFFFFF),
            fontSize = if (isSelected) 12.sp else 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 選択中フォルダの画像をGoogleフォト（インストールされていれば直接、なければ共有シート）へ一括送信する
 */
private fun shareFolderToGooglePhotos(
    context: Context,
    folderName: String,
    uris: List<Uri>
) {
    val arrayListUris = ArrayList(uris)
    val googlePhotosPackage = "com.google.android.apps.photos"

    val baseIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/jpeg"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListUris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val pm = context.packageManager
    val googlePhotosInstalled = try {
        pm.getPackageInfo(googlePhotosPackage, 0)
        true
    } catch (_: Exception) {
        false
    }

    if (googlePhotosInstalled) {
        val directIntent = Intent(baseIntent).apply {
            setPackage(googlePhotosPackage)
        }
        try {
            context.startActivity(directIntent)
            return
        } catch (_: Exception) {
            // フォールバックで共有チューザーを開く
        }
    }

    val chooser = Intent.createChooser(baseIntent, "「$folderName」(${uris.size}枚)をアップロード")
    context.startActivity(chooser)
}

@Composable
private fun MemoInputOverlay(
    capturedBitmap: Bitmap,
    selectedFolder: String,
    memoText: String,
    onMemoChange: (String) -> Unit,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDiscard: () -> Unit,
    onSaveAndNext: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    BackHandler(onBack = onDiscard)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // 上部：撮影画像の4:3プレビュー表示（チェックON時は右上に✓マークをリアルタイム表示）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = capturedBitmap.asImageBitmap(),
                contentDescription = "撮影プレビュー",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            if (isChecked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF234F2E))
                        .border(3.dp, Color(0xFFFFFFFF), CircleShape)
                        .clickable { onCheckedChange(false) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓",
                        color = Color(0xFFFFFFFF),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // 下部：石色（#FFFFFF）の入力エリア（保存先フォルダ表示＋ワンタップチェックボタン＋スリムメモ欄＋保存ボタン）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFFFFF))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // 上段：保存先フォルダ表示 ＆ ワンタップ「✓ チェック（仮決め）」ボタン
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📁 $selectedFolder",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1C1C1A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // ボタン式チェック（仮決め）トグル
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isChecked) Color(0xFF234F2E) else Color(0xFFD6D3CC)
                        )
                        .border(
                            width = 1.5.dp,
                            color = if (isChecked) Color(0xFF1C1C1A) else Color(0xFF8E8B82),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onCheckedChange(!isChecked) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓ チェック（仮決め）",
                        color = if (isChecked) Color(0xFFFFFFFF) else Color(0xFF1C1C1A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            OutlinedTextField(
                value = memoText,
                onValueChange = onMemoChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("メモを入力（空欄でも保存可）", fontSize = 14.sp) },
                minLines = 1,
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "やり直し",
                        color = Color(0xFF1C1C1A),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onSaveAndNext,
                    modifier = Modifier
                        .weight(1.6f)
                        .height(42.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isChecked) Color(0xFF234F2E) else Color(0xFF1C1C1A),
                        contentColor = Color(0xFFFFFFFF)
                    )
                ) {
                    Text(
                        text = "保存して次へ",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 4日間の全機能無料体験終了後に表示される「材集カメラ Pro（買い切り・永久ライセンス ¥6,800）」案内画面
 */
@Composable
private fun ProPaywallScreen(
    licenseState: LicenseUiState,
    onPurchaseClick: () -> Unit,
    onRestoreClick: () -> Unit,
    onSecretUnlockSubmit: (String) -> Boolean
) {
    var showSecretDialog by remember { mutableStateOf(false) }
    var secretCodeInput by remember { mutableStateOf("") }

    if (showSecretDialog) {
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { showSecretDialog = false },
            title = {
                Text(
                    text = "ライセンスコード入力",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "オーナー・専用ライセンスコードをお持ちの場合は入力してください。",
                        fontSize = 13.sp,
                        color = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = secretCodeInput,
                        onValueChange = { secretCodeInput = it },
                        placeholder = { Text("コードを入力") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (onSecretUnlockSubmit(secretCodeInput)) {
                            secretCodeInput = ""
                            showSecretDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF234F2E),
                        contentColor = Color.White
                    )
                ) {
                    Text("解除する", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSecretDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF161615))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 100年残る光学（樹木▲）＋3本水平線アイコン（タップでもライセンスコード入力ダイアログを開ける）
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFFFFF))
                    .clickable {
                        secretCodeInput = ""
                        showSecretDialog = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(width = 48.dp, height = 38.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1C1C1A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "▲",
                                color = Color(0xFFFFFFFF),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 1.dp, end = 2.dp)
                                .size(6.5.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF234F2E))
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier.size(width = 44.dp, height = 16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 2.8.dp)
                                .background(Color(0xFF1C1C1A))
                        )
                        Spacer(modifier = Modifier.height(3.6.dp))
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 2.8.dp)
                                .background(Color(0xFF1C1C1A))
                        )
                        Spacer(modifier = Modifier.height(3.6.dp))
                        Box(
                            modifier = Modifier
                                .size(width = 26.dp, height = 2.8.dp)
                                .background(Color(0xFF234F2E))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x33234F2E))
                    .border(1.dp, Color(0xFF234F2E), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (licenseState.isClockTampered) {
                        "端末の時計設定を確認してください"
                    } else {
                        "4日間の無料体験期間が終了しました"
                    },
                    color = Color(0xFFFFFFFF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "材集カメラ Pro",
                color = Color(0xFFFFFFFF),
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "現場の相棒として、確実に選び・記し・残す",
                color = Color(0xFFA6A39B),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(22.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1C))
                    .border(1.dp, Color(0xFF2E2E2B), RoundedCornerShape(16.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FeatureRow(
                    title = "買い切り・永久ライセンス（サブスクなし）",
                    desc = "一度のご購入で、今後ずっと追加課金なしで全機能をお使いいただけます。"
                )
                FeatureRow(
                    title = "現場仕様の案件フォルダ分け・仮決めチェック・GPS記録",
                    desc = "山林・農園・圃場などの電波が不安定な環境でも、端末内だけで確実に動作します。"
                )
                FeatureRow(
                    title = "機種変更してもそのまま復元",
                    desc = "Googleアカウントに紐づくため、将来端末を買い替えても無料で復元できます。"
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onPurchaseClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF234F2E),
                    contentColor = Color(0xFFFFFFFF)
                )
            ) {
                Text(
                    text = "${licenseState.formattedPrice} で永久解放する（買い切り）",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onRestoreClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "購入を復元する（機種変更・再インストール時）",
                    color = Color(0xFFFFFFFF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = {
                    secretCodeInput = ""
                    showSecretDialog = true
                }
            ) {
                Text(
                    text = "ライセンスコードを入力",
                    color = Color(0xFF8E8B82),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(0xFF234F2E)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✓",
                color = Color(0xFFFFFFFF),
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Spacer(modifier = Modifier.size(10.dp))
        Column {
            Text(
                text = title,
                color = Color(0xFFFFFFFF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                color = Color(0xFFA6A39B),
                fontSize = 12.sp
            )
        }
    }
}
