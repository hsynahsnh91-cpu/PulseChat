package com.example.ui.auth

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.R
import com.example.domain.QrCodec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle-safe Camera2 QR scanner composable.
 *
 * - Requests `Manifest.permission.CAMERA` only when displayed.
 * - Immediately closes the camera session, capture device, `ImageReader`, and background thread
 *   when the composable leaves composition, when `active` becomes false, or when the Activity
 *   pauses/stops (never leaves the camera running in the background).
 * - Provides a one-tap fallback to manual pairing-code entry if camera permission is denied or
 *   no rear/front camera is available.
 */
@Composable
fun QrCameraScannerBox(
    active: Boolean,
    onQrDetected: (String) -> Unit,
    onSwitchToManualCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnQrDetected by rememberUpdatedState(onQrDetected)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequestedOnce by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionRequestedOnce = true
    }

    LaunchedEffect(active) {
        if (active && !hasPermission && !permissionRequestedOnce) {
            permissionRequestedOnce = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasPermission) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.device_link_camera_permission_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.device_link_camera_permission_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.device_link_grant_camera))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSwitchToManualCode,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Keyboard, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.device_link_use_manual_code))
            }
        }
        return
    }

    if (cameraError) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.device_link_camera_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSwitchToManualCode) {
                Icon(Icons.Default.Keyboard, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.device_link_use_manual_code))
            }
        }
        return
    }

    val controller = remember {
        QrCameraController(
            context = context.applicationContext,
            onDetected = { text -> latestOnQrDetected(text) },
            onError = { cameraError = true },
        )
    }

    DisposableEffect(lifecycleOwner, active, hasPermission) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (active && hasPermission) controller.start()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> controller.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (active && hasPermission && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            controller.start()
        } else {
            controller.stop()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            controller.attachSurface(surface)
                        }
                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            controller.detachSurface()
                            return true
                        }
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Viewfinder frame overlay
        val accentColor = MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier.fillMaxSize()) {
            val boxSide = size.minDimension * 0.68f
            val left = (size.width - boxSide) / 2f
            val top = (size.height - boxSide) / 2f
            drawRoundRect(
                color = accentColor,
                topLeft = Offset(left, top),
                size = Size(boxSide, boxSide),
                cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                style = Stroke(width = 3.dp.toPx()),
            )
        }

        Text(
            text = stringResource(R.string.device_link_scan_hint),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(14.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private class QrCameraController(
    private val context: Context,
    private val onDetected: (String) -> Unit,
    private val onError: () -> Unit,
) {
    private val mainHandler = Handler(context.mainLooper)
    private val decoding = AtomicBoolean(false)
    @Volatile private var wantStarted = false
    @Volatile private var surfaceTexture: SurfaceTexture? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    @Volatile private var lastScanAtMs = 0L

    fun attachSurface(surface: SurfaceTexture) {
        surfaceTexture = surface
        if (wantStarted) openIfReady()
    }

    fun detachSurface() {
        surfaceTexture = null
        stopCameraInternal()
    }

    fun start() {
        wantStarted = true
        openIfReady()
    }

    fun stop() {
        wantStarted = false
        stopCameraInternal()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun openIfReady() {
        if (!wantStarted || cameraDevice != null) return
        val texture = surfaceTexture ?: return
        try {
            val manager = context.getSystemService(CameraManager::class.java) ?: run {
                mainHandler.post(onError)
                return
            }
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: run {
                mainHandler.post(onError)
                return
            }

            if (bgThread == null) {
                bgThread = HandlerThread("PulseQrScanner").also {
                    it.start()
                    bgHandler = Handler(it.looper)
                }
            }
            val handler = bgHandler ?: return

            texture.setDefaultBufferSize(640, 480)
            val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ ir ->
                val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastScanAtMs < 180L || !decoding.compareAndSet(false, true)) {
                    img.close()
                    return@setOnImageAvailableListener
                }
                lastScanAtMs = now
                try {
                    val plane = img.planes.firstOrNull()
                    if (plane != null) {
                        val width = img.width
                        val height = img.height
                        val rowStride = plane.rowStride
                        val pixelStride = plane.pixelStride
                        val buf = plane.buffer
                        val lum = ByteArray(width * height)
                        val rowBuf = ByteArray(rowStride)
                        for (y in 0 until height) {
                            val pos = y * rowStride
                            if (pos >= buf.limit()) break
                            buf.position(pos)
                            val toRead = minOf(rowStride, buf.remaining())
                            buf.get(rowBuf, 0, toRead)
                            if (pixelStride == 1) {
                                System.arraycopy(rowBuf, 0, lum, y * width, minOf(width, toRead))
                            } else {
                                var x = 0
                                while (x < width && x * pixelStride < toRead) {
                                    lum[y * width + x] = rowBuf[x * pixelStride]
                                    x++
                                }
                            }
                        }
                        val decoded = QrCodec.decodeLuminance(lum, width, height)
                        if (!decoded.isNullOrBlank()) {
                            mainHandler.post { onDetected(decoded) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("QrCameraController", "frame decode error: ${e.message}")
                } finally {
                    img.close()
                    decoding.set(false)
                }
            }, handler)
            imageReader = reader

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreviewSession(camera, texture, reader.surface, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    mainHandler.post(onError)
                }
            }, handler)
        } catch (e: Exception) {
            Log.w("QrCameraController", "openCamera failed: ${e.message}")
            mainHandler.post(onError)
        }
    }

    private fun startPreviewSession(
        camera: CameraDevice,
        texture: SurfaceTexture,
        readerSurface: Surface,
        handler: Handler,
    ) {
        try {
            val previewSurface = Surface(texture)
            val targets = listOf(previewSurface, readerSurface)
            @Suppress("DEPRECATION")
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null || !wantStarted) {
                        runCatching { session.close() }
                        return
                    }
                    captureSession = session
                    try {
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            addTarget(readerSurface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        session.setRepeatingRequest(req, null, handler)
                    } catch (e: Exception) {
                        Log.w("QrCameraController", "setRepeatingRequest failed: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    mainHandler.post(onError)
                }
            }, handler)
        } catch (e: Exception) {
            Log.w("QrCameraController", "startPreviewSession failed: ${e.message}")
        }
    }

    @Synchronized
    private fun stopCameraInternal() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { imageReader?.close() }
        imageReader = null
        bgThread?.let { thread ->
            runCatching { thread.quitSafely() }
        }
        bgThread = null
        bgHandler = null
        decoding.set(false)
    }
}
