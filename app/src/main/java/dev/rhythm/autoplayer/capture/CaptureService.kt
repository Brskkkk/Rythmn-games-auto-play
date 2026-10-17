package dev.rhythm.autoplayer.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import dev.rhythm.autoplayer.MainActivity
import dev.rhythm.autoplayer.config.ProfileRepository
import dev.rhythm.autoplayer.core.CalibrationProfile
import dev.rhythm.autoplayer.core.FrameDiagnostics
import dev.rhythm.autoplayer.core.FullScreenNoteDetector
import dev.rhythm.autoplayer.core.NoteTracker
import dev.rhythm.autoplayer.input.AccessibilityInputService
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class CaptureService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var repository: ProfileRepository
    private val detector = FullScreenNoteDetector()
    private val tracker = NoteTracker()
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var overlay: DetectionOverlay? = null
    private var overlayParams: LayoutParams? = null
    @Volatile private var profile = CalibrationProfile()
    private var pixelBytes = ByteArray(0)
    private var pixels = IntArray(0)
    private var frameWidth = 0
    private var frameHeight = 0
    private var lastProcessedMs = 0L
    private var lastFpsAtMs = 0L
    private var fpsFrames = 0
    private var fps = 0f
    private var sessionId = 0
    private val scheduledInputs = ConcurrentHashMap.newKeySet<Runnable>()

    override fun onCreate() {
        super.onCreate()
        repository = ProfileRepository(this)
        workerThread = HandlerThread("screen-frame-worker").apply { start() }
        worker = Handler(workerThread.looper)
        profile = repository.load()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, worker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_UPDATE_PROFILE -> {
                profile = repository.load()
                updateOverlay()
            }
            ACTION_START -> {
                if (projection == null) startProjection(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            stopSelf()
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = runCatching { manager.getMediaProjection(resultCode, resultData) }.getOrNull()
        val activeProjection = projection
        if (activeProjection == null) {
            sendStatus("Screen capture permission unavailable")
            stopCapture()
            return
        }
        isCapturing = true
        sessionId++
        tracker.clear()
        activeProjection.registerCallback(projectionCallback, worker)
        createCaptureSurface()
        addOverlay()
        sendStatus("Capturing in observation mode")
    }

    private fun createCaptureSurface() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        frameWidth = metrics.widthPixels
        frameHeight = metrics.heightPixels
        reader?.close()
        reader = ImageReader.newInstance(frameWidth, frameHeight, PixelFormat.RGBA_8888, 2).also {
            it.setOnImageAvailableListener({ source -> processLatestImage(source) }, worker)
        }
        val surface = reader?.surface
        val existingDisplay = virtualDisplay
        if (existingDisplay != null && surface != null) {
            existingDisplay.resize(frameWidth, frameHeight, metrics.densityDpi)
            existingDisplay.setSurface(surface)
        } else {
            virtualDisplay = projection?.createVirtualDisplay(
                "Rhythm Autoplayer capture",
                frameWidth,
                frameHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                worker
            )
        }
        val pixelCount = frameWidth * frameHeight
        pixels = IntArray(pixelCount)
        pixelBytes = ByteArray(pixelCount * 4)
        updateOverlay()
    }

    private fun processLatestImage(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastProcessedMs < profile.frameIntervalMs) return
            lastProcessedMs = nowMs
            val width = image.width
            val height = image.height
            val plane = image.planes.firstOrNull() ?: return
            val buffer: ByteBuffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val requiredBytes = buffer.remaining()
            if (requiredBytes > pixelBytes.size) pixelBytes = ByteArray(requiredBytes)
            buffer.get(pixelBytes, 0, requiredBytes)
            if (pixels.size != width * height) pixels = IntArray(width * height)
            for (y in 0 until height) {
                val rowStart = y * rowStride
                val outputStart = y * width
                for (x in 0 until width) {
                    val index = rowStart + x * pixelStride
                    if (index + 3 >= requiredBytes) break
                    val red = pixelBytes[index].toInt() and 0xff
                    val green = pixelBytes[index + 1].toInt() and 0xff
                    val blue = pixelBytes[index + 2].toInt() and 0xff
                    val alpha = pixelBytes[index + 3].toInt() and 0xff
                    pixels[outputStart + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
            val started = SystemClock.elapsedRealtime()
            val frameTimeMs = image.timestamp / 1_000_000L
            val candidates = detector.detect(pixels, width, height, profile)
            val notes = tracker.update(candidates, frameTimeMs, height, profile)
            val processingMs = SystemClock.elapsedRealtime() - started
            fpsFrames++
            if (started - lastFpsAtMs >= 1000L) {
                fps = fpsFrames * 1000f / max(1L, started - lastFpsAtMs)
                fpsFrames = 0
                lastFpsAtMs = started
            }
            val captureToDetection = ((SystemClock.elapsedRealtimeNanos() - image.timestamp) / 1_000_000L).coerceAtLeast(0L)
            val averageConfidence = if (notes.isEmpty()) 0f else notes.map { it.confidence }.average().toFloat()
            val diagnostics = FrameDiagnostics(width, height, candidates.size, processingMs, captureToDetection,
                averageConfidence, fps, notes)
            broadcastDiagnostics(diagnostics)
            overlay?.update(profile, notes, width, height, "${"%.1f".format(fps)} fps  ${processingMs}ms")

            val dispatchLanes = notes.filter { it.dispatchNow }.map { it.lane }
            if (dispatchLanes.isNotEmpty() && !profile.observationOnly) {
                val frameAge = (SystemClock.elapsedRealtime() - frameTimeMs).coerceAtLeast(0L)
                val dueNotes = notes.filter { it.dispatchNow }
                val delay = (dueNotes.minOf { it.etaMs } - frameAge - profile.timingOffsetMs).coerceAtLeast(0L)
                val holds = dueNotes.associate { it.lane to it.holdDurationMs }
                scheduleInput(dispatchLanes, holds, delay, sessionId)
            }
        } catch (_: RuntimeException) {
            sendStatus("Frame processing recovered from an error")
        } finally {
            image.close()
        }
    }

    private fun scheduleInput(lanes: List<Int>, holdDurationsMs: Map<Int, Long>, delayMs: Long, expectedSession: Int) {
        val expectedDispatchMs = SystemClock.elapsedRealtime() + delayMs
        lateinit var task: Runnable
        task = Runnable {
            scheduledInputs.remove(task)
            if (projection != null && sessionId == expectedSession && !profile.observationOnly) {
                val dispatchLateness = (SystemClock.elapsedRealtime() - expectedDispatchMs).coerceAtLeast(0L)
                sendStatus("Detection-to-dispatch: ${dispatchLateness} ms")
                val accepted = AccessibilityInputService.dispatch(lanes, holdDurationsMs, profile)
                sendStatus(if (accepted) "Gesture submitted" else "Input service unavailable or gesture rejected")
            }
        }
        scheduledInputs.add(task)
        worker.postDelayed(task, delayMs)
    }

    private fun addOverlay() {
        if (!profile.overlayEnabled || !Settings.canDrawOverlays(this) || overlay != null) return
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val view = DetectionOverlay(this)
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            overlay = view
            overlayParams = params
        }
    }

    private fun updateOverlay() {
        if (profile.overlayEnabled) addOverlay() else removeOverlay()
    }

    private fun removeOverlay() {
        overlay?.let { view -> runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } }
        overlay = null
        overlayParams = null
    }

    private fun stopCapture() {
        isCapturing = false
        sessionId++
        scheduledInputs.forEach { worker.removeCallbacks(it) }
        scheduledInputs.clear()
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        removeOverlay()
        tracker.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW))
        }
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle("Rhythm Autoplayer")
            .setContentText("Screen analysis is active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun broadcastDiagnostics(stats: FrameDiagnostics) {
        val intent = Intent(ACTION_DIAGNOSTICS).setPackage(packageName)
            .putExtra("width", stats.width).putExtra("height", stats.height)
            .putExtra("detections", stats.detectionCount).putExtra("processingMs", stats.processingMs)
            .putExtra("captureLatencyMs", stats.captureToDetectionMs).putExtra("confidence", stats.confidence)
            .putExtra("fps", stats.fps)
        sendBroadcast(intent)
    }

    private fun sendStatus(status: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, status))
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (projection != null) stopCapture()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (projection != null && displayId == Display.DEFAULT_DISPLAY) worker.post { createCaptureSurface() }
        }
    }

    override fun onDestroy() {
        stopCapture()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.rhythm.autoplayer.START_CAPTURE"
        const val ACTION_STOP = "dev.rhythm.autoplayer.STOP_CAPTURE"
        const val ACTION_UPDATE_PROFILE = "dev.rhythm.autoplayer.UPDATE_PROFILE"
        const val ACTION_DIAGNOSTICS = "dev.rhythm.autoplayer.DIAGNOSTICS"
        const val ACTION_STATUS = "dev.rhythm.autoplayer.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 14
        @Volatile
        var isCapturing: Boolean = false
            private set
    }
}