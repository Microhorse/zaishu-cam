package com.example.zaishucamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    ZaishuCameraApp()
                }
            }
        }
    }
}

/**
 * 単一画面のUI状態
 */
private sealed interface CaptureUiState {
    data object CameraPreview : CaptureUiState
    data class MemoInput(
        val capturedBitmap: Bitmap,
        val capturedTimeMillis: Long
    ) : CaptureUiState
}

@Composable
fun ZaishuCameraApp() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        // 権限未許可時：画面中央にシンプルな許可要求ボタンのみを表示
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
            ) {
                Text(
                    text = "カメラ権限を許可する",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    } else {
        ZaishuMainScreen()
    }
}

@Composable
private fun ZaishuMainScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var uiState by remember { mutableStateOf<CaptureUiState>(CaptureUiState.CameraPreview) }
    var memoText by remember { mutableStateOf("") }
    var isCapturing by remember { mutableStateOf(false) }
    var lastCapturedTime by remember { mutableLongStateOf(0L) }

    // CameraX ユースケースを保持し、状態復帰時の再バインド遅延をゼロにする
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    // カメラのライフサイクルバインド（起動時に1回のみ実行し常時スタンバイ）
    LaunchedEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(context, "カメラ初期化エラー: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // カメラプレビューは常に背面に配置しておくことで、状態1へ戻った瞬間に0msで表示する
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        when (val currentState = uiState) {
            is CaptureUiState.CameraPreview -> {
                // 【状態1: カメラプレビュー状態】
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(bottom = 36.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    // 大きく押しやすい撮影ボタン
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(if (isCapturing) Color.Gray else Color.White)
                            .clickable(enabled = !isCapturing) {
                                isCapturing = true
                                val now = System.currentTimeMillis()
                                lastCapturedTime = now

                                imageCapture.takePicture(
                                    cameraExecutor,
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            // ディスクを介さずメモリ上でBitmap化＆EXIF回転の正規化
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
                                                    capturedTimeMillis = lastCapturedTime
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
                    )
                }
            }

            is CaptureUiState.MemoInput -> {
                // 【状態2: メモ即時入力状態】
                MemoInputOverlay(
                    capturedBitmap = currentState.capturedBitmap,
                    memoText = memoText,
                    onMemoChange = { memoText = it },
                    onDiscard = {
                        // やり直し（破棄）：Bitmapを解放して即座に状態1へ戻す
                        val bitmapToRecycle = currentState.capturedBitmap
                        memoText = ""
                        uiState = CaptureUiState.CameraPreview
                        bitmapToRecycle.recycle()
                    },
                    onSaveAndNext = {
                        // 【状態3: バックグラウンド合成・即時復帰】
                        val bitmapToSave = currentState.capturedBitmap
                        val textToSave = memoText
                        val timeToSave = currentState.capturedTimeMillis

                        // 1. UIを即座に【状態1: カメラプレビュー状態】へ戻し、入力欄もクリア
                        memoText = ""
                        uiState = CaptureUiState.CameraPreview

                        // 2. 裏側（Dispatchers.IO）で画像合成とMediaStore保存を実行
                        coroutineScope.launch(Dispatchers.IO) {
                            val success = ImageSynthesizer.synthesizeAndSave(
                                context = appContext,
                                originalBitmap = bitmapToSave,
                                memoText = textToSave,
                                capturedTimeMillis = timeToSave
                            )
                            withContext(Dispatchers.Main) {
                                val msg = if (success) "保存しました" else "保存に失敗しました"
                                Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MemoInputOverlay(
    capturedBitmap: Bitmap,
    memoText: String,
    onMemoChange: (String) -> Unit,
    onDiscard: () -> Unit,
    onSaveAndNext: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // 戻るボタン押下時も破棄としてカメラプレビューへ即復帰
    BackHandler(onBack = onDiscard)

    // 状態2に切り替わった瞬間、自動でフォーカスを当ててソフトキーボードを即座に立ち上げる
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
            .imePadding() // ソフトキーボード表示時に下部入力エリアとボタンを押し上げる
    ) {
        // 上部：撮影画像のプレビュー表示（キーボード表示時も残り領域に自動縮小して表示）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = capturedBitmap.asImageBitmap(),
                contentDescription = "撮影プレビュー",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 下部：白い入力エリア（入力欄のすぐ近くに操作ボタンを配置）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            OutlinedTextField(
                value = memoText,
                onValueChange = onMemoChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("メモを入力（複数行可・空欄でも保存可）") },
                minLines = 2,
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(
                        text = "やり直し(破棄)",
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onSaveAndNext,
                    modifier = Modifier
                        .weight(1.6f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1565C0),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "保存して次へ",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
