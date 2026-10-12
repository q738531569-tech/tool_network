package com.installer.toolbox.ui.screens.tools

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.installer.toolbox.core.network.discovery.BarcodeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 二维码/条码功能（栏目级，单入口）。
 *
 * 页面结构（**扫码页与生成页独立**，避免相机实时画面遮挡其他 UI——
 * 相机渲染 layer 浮在视图之上，同页 Tab 会被盖住，独立页面彻底规避）：
 * - 选择页：两个入口卡片（扫码识别 / 码生成）
 * - 扫码页：CameraX 实时预览（闪光灯可控，暗光适配）+ 相册选图兜底，逐帧解码
 * - 生成页：文本 → 二维码/条码（QR / CODE_128 / EAN_13 / CODE_39）
 *
 * @param initialTab 兼容旧参数：1 直接进生成页，其余进选择页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeScreen(initialTab: Int = 0) {
    // 页面切换（页面内状态，不走导航）：choose / scan / generate
    var page by remember { mutableStateOf(if (initialTab == 1) "generate" else "choose") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    when (page) {
        "choose" -> BarcodeChoosePage(
            onScan = { page = "scan" },
            onGenerate = { page = "generate" }
        )
        "scan" -> ScanScreen(
            onBack = { page = "choose" },
            snackbarHostState = snackbarHostState,
            onResult = { text, format ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (text != null) "识别成功：$text" else "未识别出二维码/条码"
                    )
                }
            }
        )
        "generate" -> GenerateScreen(onBack = { page = "choose" }, snackbarHostState = snackbarHostState)
    }
}

/** 选择页：两个功能入口卡片 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarcodeChoosePage(onScan: () -> Unit, onGenerate: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("二维码/条码功能") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EntryCard(
                title = "扫码识别",
                desc = "相机实时扫码 + 相册选图，支持二维码与常见条码",
                icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                onClick = onScan
            )
            EntryCard(
                title = "码生成",
                desc = "生成二维码（QR）/ Code128 / EAN13 / Code39",
                icon = { Icon(Icons.Filled.QrCode, contentDescription = null) },
                onClick = onGenerate
            )
        }
    }
}

/** 入口卡片 */
@Composable
private fun EntryCard(
    title: String,
    desc: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 扫码页：全屏结构，相机预览独占中间区域（独立页面，不与其他 UI 同屏） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanScreen(
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onResult: (String?, BarcodeFormat?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }

    // 相机权限：运行时申请
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 保存 CameraProvider 引用：离开扫码页时解绑相机，避免后台占用与重复绑定
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
            }
        }
    }

    // 闪光灯状态
    var torchOn by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var bound by remember { mutableStateOf(false) }
    // 解码状态
    var analyzing by remember { mutableStateOf(true) }
    var decodedText by remember { mutableStateOf<String?>(null) }
    var decodedFormat by remember { mutableStateOf<String?>(null) }

    // 相册选图兜底
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val bitmap = loadBitmap(context, uri)
            if (bitmap != null) {
                val pair = withContextSafe { BarcodeUtils.decode(bitmap) }
                if (pair != null) {
                    decodedText = pair.first
                    decodedFormat = pair.second.name
                    onResult(pair.first, pair.second)
                } else {
                    decodedText = null
                    decodedFormat = null
                    onResult(null, null)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫码识别") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasCameraPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "扫码需要相机权限",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("授予相机权限")
                    }
                }
            } else {
                // 相机预览独占中间区域（weight 1f），控制栏固定在下方——独立页面不再有同屏 UI 可被遮挡
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            // 关键：TextureView 实现（SurfaceView 是独立 layer 会浮在其他视图之上）
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    update = { previewView ->
                        if (!bound) {
                            bound = true
                            val providerFuture = ProcessCameraProvider.getInstance(context)
                            providerFuture.addListener(
                                {
                                    try {
                                        val provider = providerFuture.get()
                                        cameraProvider = provider
                                        val preview = Preview.Builder().build()
                                            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                                        val analysis = ImageAnalysis.Builder()
                                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                            .build()
                                        analysis.setAnalyzer(
                                            analyzerExecutor,
                                            ImageAnalysis.Analyzer { imageProxy ->
                                                if (!analyzing) {
                                                    imageProxy.close()
                                                    return@Analyzer
                                                }
                                                val result = decodeYuvFrame(imageProxy)
                                                imageProxy.close()
                                                if (result != null && analyzing) {
                                                    analyzing = false
                                                    decodedText = result.first
                                                    decodedFormat = result.second.name
                                                    onResult(result.first, result.second)
                                                }
                                            }
                                        )
                                        val camera = provider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analysis
                                        )
                                        cameraControl = camera.cameraControl
                                    } catch (e: Exception) {
                                        // 相机不可用（无后摄等）静默，页面保留相册入口
                                    }
                                },
                                ContextCompat.getMainExecutor(context)
                            )
                        }
                    }
                )
                // 控制栏（预览下方）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = {
                        torchOn = !torchOn
                        cameraControl?.enableTorch(torchOn)
                    }) {
                        Text(if (torchOn) "关闭闪光灯" else "开启闪光灯")
                    }
                    OutlinedButton(
                        onClick = {
                            pickLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    ) { Text("从相册选择") }
                    if (decodedText != null) {
                        OutlinedButton(onClick = {
                            analyzing = true
                            decodedText = null
                        }) { Text("继续扫码") }
                    }
                }
                // 解码结果
                decodedText?.let { text ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = "识别结果${decodedFormat?.let { "（$it）" } ?: ""}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { copyToClipboard(context, text) }) {
                                Text("复制结果")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 生成页：文本 → 二维码/条码（独立页面） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenerateScreen(onBack: () -> Unit, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    var content by remember { mutableStateOf("") }
    var formatIndex by remember { mutableIntStateOf(0) }
    val formats = BarcodeUtils.GENERATE_FORMATS
    var generated by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun generate() {
        if (content.isBlank()) {
            error = "请输入要生成的内容"
            generated = null
            return
        }
        val bmp = withContextSafe { BarcodeUtils.encode(content, formats[formatIndex]) }
        if (bmp != null) {
            generated = bmp
            error = null
        } else {
            generated = null
            error = "生成失败：内容不符合所选格式要求（EAN-13 需 12-13 位数字，Code128 内容不宜过长）"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("码生成") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("文本 / 链接 / 数字") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                formats.forEachIndexed { index, format ->
                    OutlinedButton(onClick = { formatIndex = index }) {
                        Text(
                            text = format.name,
                            color = if (index == formatIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = ::generate, modifier = Modifier.fillMaxWidth()) { Text("生成") }
            Spacer(Modifier.height(16.dp))
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            generated?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "生成的码",
                    modifier = Modifier
                        .size(if (formatIndex == 0) 260.dp else 300.dp)
                        .padding(4.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { copyToClipboard(context, content) }) {
                    Text("复制内容")
                }
            }
        }
    }
}

/** 解码 CameraX 帧（YUV_420_888 → NV21 → 按传感器旋转 → ZXing）；解码失败返回 null */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun decodeYuvFrame(imageProxy: ImageProxy): Pair<String, BarcodeFormat>? {
    return try {
        val image = imageProxy.image ?: return null
        val width = image.width
        val height = image.height
        val planes = image.planes
        val nv21 = yuv420ToNv21(
            planes[0], planes[1], planes[2], width, height
        )
        // 竖屏手持时传感器输出旋转 90°，横条码必须旋转后解码（否则 Code128 等识别率极低）
        val rotation = imageProxy.imageInfo.rotationDegrees
        val (rotated, newW, newH) = rotateNv21(nv21, width, height, rotation)
        val source = PlanarYUVLuminanceSource(rotated, newW, newH, 0, 0, newW, newH, false)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A,
                BarcodeFormat.ITF,
                BarcodeFormat.CODABAR,
                BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.AZTEC,
                BarcodeFormat.PDF_417
            )
        )
        val result = MultiFormatReader().apply { setHints(hints) }.decode(binary)
        result.text to result.barcodeFormat
    } catch (e: Exception) {
        null
    }
}

/**
 * NV21 图像按传感器旋转角度（顺时针 0/90/180/270）旋转。
 * 返回 (旋转后数据, 新宽, 新高)；Y 平面与 UV 平面分别旋转。
 */
private fun rotateNv21(
    src: ByteArray,
    width: Int,
    height: Int,
    rotation: Int
): Triple<ByteArray, Int, Int> {
    val rot = ((rotation % 360) + 360) % 360
    if (rot == 0) return Triple(src, width, height)
    val uvStart = width * height
    val out = ByteArray(src.size)
    when (rot) {
        90 -> {
            val nw = height
            val nh = width
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    out[y * nw + x] = src[(nw - 1 - x) * nh + y]
                }
            }
            val uvW = nw / 2
            val uvH = nh / 2
            for (y in 0 until uvH) {
                for (x in 0 until uvW) {
                    val srcIdx = uvStart + ((uvW - 1 - x) * uvH + y) * 2
                    val dstIdx = uvStart + (y * uvW + x) * 2
                    out[dstIdx] = src[srcIdx]         // V
                    out[dstIdx + 1] = src[srcIdx + 1] // U
                }
            }
            return Triple(out, nw, nh)
        }
        180 -> {
            for (i in 0 until uvStart) {
                out[i] = src[uvStart - 1 - i]
            }
            val uvLen = (src.size - uvStart) / 2
            for (i in 0 until uvLen) {
                out[uvStart + i * 2] = src[src.size - 2 - i * 2]
                out[uvStart + i * 2 + 1] = src[src.size - 1 - i * 2]
            }
            return Triple(out, width, height)
        }
        270 -> {
            val nw = height
            val nh = width
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    out[y * nw + x] = src[x * nh + (nh - 1 - y)]
                }
            }
            val uvW = nw / 2
            val uvH = nh / 2
            for (y in 0 until uvH) {
                for (x in 0 until uvW) {
                    val srcIdx = uvStart + (x * uvH + (uvH - 1 - y)) * 2
                    val dstIdx = uvStart + (y * uvW + x) * 2
                    out[dstIdx] = src[srcIdx]
                    out[dstIdx + 1] = src[srcIdx + 1]
                }
            }
            return Triple(out, nw, nh)
        }
        else -> return Triple(src, width, height)
    }
}

/**
 * YUV_420_888（CameraX 默认）→ NV21 字节数组。
 * 正确处理 rowStride/pixelStride 与 UV 平面（NV21 = Y + VU 交错）。
 */
private fun yuv420ToNv21(
    yPlane: android.media.Image.Plane,
    uPlane: android.media.Image.Plane,
    vPlane: android.media.Image.Plane,
    width: Int,
    height: Int
): ByteArray {
    val nv21 = ByteArray(width * height * 3 / 2)
    // Y 平面
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    var yPos = 0
    for (row in 0 until height) {
        var rowStart = row * yRowStride
        for (col in 0 until width) {
            nv21[yPos++] = yBuffer.get(rowStart)
            rowStart += yPixelStride
        }
    }
    // UV 平面（NV21 = V 在前、U 在后交错；半分辨率）
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride
    val uvWidth = width / 2
    val uvHeight = height / 2
    var uvPos = width * height
    for (row in 0 until uvHeight) {
        var uRow = row * uvRowStride
        var vRow = row * vPlane.rowStride
        for (col in 0 until uvWidth) {
            nv21[uvPos++] = vBuffer.get(vRow)
            nv21[uvPos++] = uBuffer.get(uRow)
            uRow += uvPixelStride
            vRow += vPlane.pixelStride
        }
    }
    return nv21
}

/** 在 IO 线程执行并返回结果（页面内小工具） */
private fun <T> withContextSafe(block: () -> T): T? {
    return try {
        kotlinx.coroutines.runBlocking {
            withContext(Dispatchers.IO) { block() }
        }
    } catch (e: Exception) {
        null
    }
}

/** 从相册 Uri 读取 Bitmap（尽量按原图解码，失败返回 null） */
private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }
}

/** 复制文本到系统剪贴板 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("扫码工具", text))
}
