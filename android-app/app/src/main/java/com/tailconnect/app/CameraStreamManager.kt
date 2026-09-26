package com.tailconnect.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat

object CameraStreamManager {

    private const val TAG = "CameraStreamManager"

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var onFrameCallback: ((ByteArray) -> Unit)? = null
    private var currentFacingFront = false
    private var isTorchEnabled = false

    var isStreaming = false
        private set

    fun isFacingFront(): Boolean = currentFacingFront
    fun isTorchOn(): Boolean = isTorchEnabled

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraStreamBackground").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (_: Exception) {}
        backgroundThread = null
        backgroundHandler = null
    }

    private var lastFrameTime = 0L
    private const val MIN_FRAME_INTERVAL_MS = 66L // 15 FPS throttle for smooth, low-latency live streaming

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startCamera(
        context: Context,
        front: Boolean = false,
        onFrame: (ByteArray) -> Unit
    ): Boolean {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start camera: CAMERA permission not granted")
            return false
        }

        stopCamera()
        onFrameCallback = onFrame
        currentFacingFront = front
        isTorchEnabled = false
        lastFrameTime = 0L
        startBackgroundThread()

        try {
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetFacing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val cameraId = findCameraId(cameraManager!!, targetFacing) ?: cameraManager!!.cameraIdList.firstOrNull()

            if (cameraId == null) {
                Log.e(TAG, "No suitable camera ID found")
                return false
            }

            val characteristics = cameraManager!!.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSize = chooseOptimalSize(map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray(), 480, 640)
            Log.i(TAG, "Selected camera stream resolution: ${outputSize.width}x${outputSize.height}")

            imageReader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameTime < MIN_FRAME_INTERVAL_MS) {
                        val dropped = reader.acquireLatestImage()
                        dropped?.close()
                        return@setOnImageAvailableListener
                    }

                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    lastFrameTime = now

                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    if (isStreaming) {
                        onFrameCallback?.invoke(bytes)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error acquiring camera frame: ${e.message}")
                }
            }, backgroundHandler)

            cameraManager!!.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    isStreaming = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    isStreaming = false
                    Log.e(TAG, "Camera open error: $error")
                }
            }, backgroundHandler)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera: ${e.message}")
            stopCamera()
            return false
        }
    }

    private fun createSession(camera: CameraDevice) {
        val readerSurface = imageReader?.surface ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(readerSurface)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.JPEG_QUALITY, 42.toByte()) // 42% quality reduces payload by ~75% with zero perceptible loss

            camera.createCaptureSession(listOf(readerSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    isStreaming = true
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        Log.i(TAG, "Camera streaming session active (FacingFront: $currentFacingFront)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set repeating capture request: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera session configuration failed")
                    isStreaming = false
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create camera capture session: ${e.message}")
        }
    }

    private fun findCameraId(manager: CameraManager, facing: Int): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
            if (lensFacing == facing) {
                return id
            }
        }
        return null
    }

    private fun chooseOptimalSize(choices: Array<Size>, targetWidth: Int = 480, targetHeight: Int = 640): Size {
        // Target ~480p preview (e.g. 640x480, 854x480, 720x480) without exceeding 960x720 / 720x960
        val targetPixels = targetWidth * targetHeight
        val candidates = choices.filter { (it.width <= 960 && it.height <= 720) || (it.width <= 720 && it.height <= 960) }
        return candidates.minByOrNull { Math.abs(it.width * it.height - targetPixels) }
            ?: (choices.filter { (it.width <= 1280 && it.height <= 720) || (it.width <= 720 && it.height <= 1280) }
                .minByOrNull { Math.abs(it.width * it.height - targetPixels) }
                ?: (choices.firstOrNull() ?: Size(640, 480)))
    }

    @Synchronized
    fun switchCamera(context: Context): Boolean {
        val callback = onFrameCallback ?: return false
        return startCamera(context, !currentFacingFront, callback)
    }

    @Synchronized
    fun toggleTorch(context: Context): Boolean {
        if (currentFacingFront) return false // Front camera usually doesn't have a physical flash
        try {
            val manager = cameraManager ?: (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
            val rearCameraId = findCameraId(manager, CameraCharacteristics.LENS_FACING_BACK) ?: return false
            isTorchEnabled = !isTorchEnabled
            manager.setTorchMode(rearCameraId, isTorchEnabled)
            Log.i(TAG, "Torch mode set to: $isTorchEnabled")
            return isTorchEnabled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to toggle torch: ${e.message}")
            return false
        }
    }

    @Synchronized
    fun stopCamera() {
        isStreaming = false
        isTorchEnabled = false
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (_: Exception) {}
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}
        cameraDevice = null

        imageReader?.close()
        imageReader = null

        stopBackgroundThread()
        Log.i(TAG, "Camera streaming stopped")
    }
}
