package dev.rhythm.autoplayer.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import dev.rhythm.autoplayer.MainActivity
import dev.rhythm.autoplayer.config.ProfileRepository
import dev.rhythm.autoplayer.core.CalibrationProfile
import dev.rhythm.autoplayer.core.FullScreenNoteDetector
import dev.rhythm.autoplayer.core.NoteTracker
import dev.rhythm.autoplayer.core.PixelReader
import dev.rhythm.autoplayer.input.AccessibilityInputService
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service that owns screen capture, the draggable receptor overlay, the control panel,
 * and the detect -> predict -> tap loop. Frames are processed in memory and never stored.
 */
class CaptureService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private val main = Handler(Looper.getMainLooper())
    private lateinit var repository: ProfileRepository
    private lateinit var windowManager: WindowManager
    private val detector = FullScreenNoteDetector()
    private val tracker = NoteTracker()

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var overlay: ReceptorOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var panel: ControlPanel? = null

    @Volatile private var profile = CalibrationProfile()
    @Volatile private var mode = AutoMode.EDIT
    @Volatile private var sessionId = 0
    private var stopped = false
    private var frameWidth = 0
    private var frameHeight = 0
    private var lastProcessedMs = 0L
    private var lastFpsAtMs = 0L
    private var fpsFrames = 0
    private var fps = 0f
    private var lastStatusAtMs = 0L
    private var lastLatenessMs = 0L
    private val scheduled = ConcurrentHashMap.newKeySet<Runnable>()

    override fun onCreate() {
        super.onCreate()
        repository = ProfileRepository(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        workerThread = HandlerThread("screen-frame-worker").apply { start() }
        worker = Handler(workerThread.looper)
        profile = repository.load()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent
        when (request?.action) {
            ACTION_START -> if (request != null) startSession(request)
            ACTION_STOP -> stopSession()
            ACTION_UPDATE_PROFILE -> {
                reloadProfile()
                if (projection == null) stopSelf()
            }
            else -> if (projection == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        // Must happen immediately after startForegroundService() or the system kills the app.
        startForegroundCompat()
        if (projection != null) return
        if (!Settings.canDrawOverlays(this)) {
            toast("Grant 'Display over other apps' first")
            stopSession()
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            stopSession()
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val active = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (_: RuntimeException) {
            null
        }
        if (active == null) {
            toast("Screen capture permission unavailable")
            stopSession()
            return
        }
        projection = active
        sessionId++
        stopped = false
        isRunning = true
        profile = repository.load()
        active.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSession() }
            }
        }, worker)
        try {
            createCaptureSurface()
            addWindows()
        } catch (e: RuntimeException) {
            toast("Could not start capture: ${e.message}")
            stopSession()
            return
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, worker)
        toast("Drag the circles onto your receptors")
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- capture ------------------------------------------------------------------------

    private fun createCaptureSurface() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val realWidth = metrics.widthPixels
        val realHeight = metrics.heightPixels
        val scale = min(1f, CAPTURE_LONG_SIDE / max(realWidth, realHeight).toFloat())
        val newWidth = max(64, (realWidth * scale).toInt())
        val newHeight = max(64, (realHeight * scale).toInt())
        if (virtualDisplay != null && newWidth == frameWidth && newHeight == frameHeight) return

        val oldReader = reader
        val newReader = ImageReader.newInstance(newWidth, newHeight, PixelFormat.RGBA_8888, 3)
        newReader.setOnImageAvailableListener({ source -> processLatestImage(source) }, worker)
        val existing = virtualDisplay
        if (existing != null) {
            existing.resize(newWidth, newHeight, metrics.densityDpi)
            existing.surface = newReader.surface
        } else {
            virtualDisplay = projection?.createVirtualDisplay(
                "Rhythm Autoplayer capture",
                newWidth, newHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, worker
            )
        }
        reader = newReader
        frameWidth = newWidth
        frameHeight = newHeight
        oldReader?.setOnImageAvailableListener(null, null)
        oldReader?.close()
        tracker.clear()
    }

    private fun processLatestImage(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            val currentMode = mode
            if (currentMode == AutoMode.EDIT) return
            val arrival = SystemClock.elapsedRealtime()
            val config = profile
            if (arrival - lastProcessedMs < config.frameIntervalMs) return
            lastProcessedMs = arrival

            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val limit = buffer.limit()
            val width = image.width
            val height = image.height
            val pixels = PixelReader { x, y ->
                val i = y * rowStride + x * pixelStride
                if (i + 2 >= limit) {
                    0
                } else {
                    ((buffer.get(i).toInt() and 0xff) shl 16) or
                        ((buffer.get(i + 1).toInt() and 0xff) shl 8) or
                        (buffer.get(i + 2).toInt() and 0xff)
                }
            }

            val candidates = detector.detect(pixels, width, height, config)
            val notes = tracker.update(candidates, arrival, height, config)
            val doneAt = SystemClock.elapsedRealtime()
            val processingMs = doneAt - arrival

            fpsFrames++
            if (doneAt - lastFpsAtMs >= 1000L) {
                fps = fpsFrames * 1000f / max(1L, doneAt - lastFpsAtMs)
                fpsFrames = 0
                lastFpsAtMs = doneAt
            }
            overlay?.setMarkers(notes, height)

            if (currentMode == AutoMode.PLAY) {
                val due = notes.filter { it.dispatchNow }
                if (due.isNotEmpty()) {
                    val frameAge = doneAt - arrival
                    val timed = due.map { it to max(0L, it.etaMs - frameAge - config.timingOffsetMs) }
                        .sortedBy { it.second }
                    var i = 0
                    while (i < timed.size) {
                        val base = timed[i].second
                        val lanes = ArrayList<Int>()
                        while (i < timed.size && timed[i].second - base <= 12L) {
                            lanes.add(timed[i].first.lane)
                            i++
                        }
                        scheduleTap(lanes, base)
                    }
                }
            }

            if (doneAt - lastStatusAtMs >= 250L) {
                lastStatusAtMs = doneAt
                val text = "%.0f fps  %d ms  notes %d  late %d ms\n%s".format(
                    fps, processingMs, notes.size, lastLatenessMs, AccessibilityInputService.statusLine()
                )
                main.post { panel?.setStatus(text) }
            }
        } catch (_: RuntimeException) {
            // Keep the capture loop alive; a single bad frame must not end the session.
        } finally {
            image.close()
        }
    }

    private fun scheduleTap(lanes: List<Int>, delayMs: Long) {
        val expected = sessionId
        val dueAt = SystemClock.elapsedRealtime() + delayMs
        val holder = arrayOfNulls<Runnable>(1)
        val task = Runnable {
            holder[0]?.let { scheduled.remove(it) }
            if (mode == AutoMode.PLAY && sessionId == expected) {
                lastLatenessMs = max(0L, SystemClock.elapsedRealtime() - dueAt)
                if (!AccessibilityInputService.tap(lanes, profile)) {
                    main.post { panel?.setStatus("Accessibility service is not enabled") }
                }
            }
        }
        holder[0] = task
        scheduled.add(task)
        worker.postDelayed(task, delayMs)
    }

    private fun cancelScheduled() {
        scheduled.forEach { worker.removeCallbacks(it) }
        scheduled.clear()
    }

    // ---- overlay + panel ----------------------------------------------------------------

    private fun addWindows() {
        val view = ReceptorOverlayView(this)
        view.setProfile(profile)
        view.editing = true
        view.onChanged = { persistReceptors() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        windowManager.addView(view, params)
        overlay = view
        overlayParams = params

        val control = ControlPanel(this, panelListener)
        control.setLaneCount(profile.laneCount)
        control.attach(windowManager)
        panel = control
    }

    private fun removeWindows() {
        overlay?.let { view -> runCatching { windowManager.removeView(view) } }
        overlay = null
        overlayParams = null
        panel?.detach()
        panel = null
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        val view = overlay ?: return
        val params = overlayParams ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun setMode(newMode: AutoMode) {
        val previous = mode
        if (previous == newMode) return
        if (previous == AutoMode.EDIT) persistReceptors()
        mode = newMode
        sessionId++
        cancelScheduled()
        tracker.clear()
        overlay?.editing = newMode == AutoMode.EDIT
        overlay?.setMarkers(emptyList(), max(1, frameHeight))
        setOverlayTouchable(newMode == AutoMode.EDIT)
        panel?.showMode(newMode)
        panel?.setStatus(
            when (newMode) {
                AutoMode.EDIT -> "Drag the circles onto the receptors"
                AutoMode.WATCH -> "Watching only - no taps. Dots show detected notes."
                AutoMode.PLAY -> "Autoplay running"
            }
        )
    }

    private fun persistReceptors() {
        val view = overlay ?: return
        profile = repository.load().withReceptors(view.snapshot())
        repository.save(profile)
    }

    private fun reloadProfile() {
        profile = repository.load()
        overlay?.setProfile(profile)
        panel?.setLaneCount(profile.laneCount)
        tracker.clear()
    }

    private fun changeLanes(delta: Int) {
        val view = overlay ?: return
        val updated = repository.load().withReceptors(view.snapshot()).withLaneCount(profile.laneCount + delta)
        repository.save(updated)
        profile = updated
        view.setProfile(updated)
        panel?.setLaneCount(updated.laneCount)
    }

    private fun runTest() {
        if (!AccessibilityInputService.isConnected()) {
            panel?.setStatus("Turn on 'Rhythm Autoplayer input' in Accessibility settings first")
            return
        }
        setMode(AutoMode.WATCH)
        panel?.setStatus("Tapping all ${profile.laneCount} receptors once...")
        val lanes = (0 until profile.laneCount).toList()
        main.postDelayed({ AccessibilityInputService.tap(lanes, profile) }, 400L)
        main.postDelayed({ panel?.setStatus(AccessibilityInputService.statusLine()) }, 1200L)
    }

    private val panelListener = object : ControlPanel.Listener {
        override fun onMode(mode: AutoMode) = setMode(mode)
        override fun onTest() = runTest()
        override fun onAddLane() = changeLanes(1)
        override fun onRemoveLane() = changeLanes(-1)
        override fun onEven() {
            overlay?.spreadEvenly()
        }
        override fun onLinkRows(enabled: Boolean) {
            overlay?.linkRows = enabled
        }
        override fun onClose() = stopSession()
    }

    // ---- lifecycle ----------------------------------------------------------------------

    private fun stopSession() {
        if (stopped) return
        stopped = true
        isRunning = false
        sessionId++
        runCatching { if (mode == AutoMode.EDIT) persistReceptors() }
        cancelScheduled()
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        removeWindows()
        tracker.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && projection != null) {
                worker.post { runCatching { createCaptureSurface() } }
            }
        }
    }

    override fun onDestroy() {
        stopSession()
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Rhythm Autoplayer")
            .setContentText("Screen analysis is active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun toast(message: String) {
        main.post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val ACTION_START = "dev.rhythm.autoplayer.START_CAPTURE"
        const val ACTION_STOP = "dev.rhythm.autoplayer.STOP_CAPTURE"
        const val ACTION_UPDATE_PROFILE = "dev.rhythm.autoplayer.UPDATE_PROFILE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 14
        private const val CAPTURE_LONG_SIDE = 1280f

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
