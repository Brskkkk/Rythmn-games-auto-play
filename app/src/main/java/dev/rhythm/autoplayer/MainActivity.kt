package dev.rhythm.autoplayer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.rhythm.autoplayer.capture.CaptureService
import dev.rhythm.autoplayer.config.ProfileRepository
import dev.rhythm.autoplayer.core.CalibrationProfile
import dev.rhythm.autoplayer.core.ScrollDirection
import dev.rhythm.autoplayer.input.AccessibilityInputService

class MainActivity : Activity() {
    private lateinit var repository: ProfileRepository
    private lateinit var profileName: EditText
    private lateinit var laneCount: SeekBar
    private lateinit var laneLabel: TextView
    private lateinit var hitLine: SeekBar
    private lateinit var hitLabel: TextView
    private lateinit var sensitivity: SeekBar
    private lateinit var sensitivityLabel: TextView
    private lateinit var confidence: SeekBar
    private lateinit var confidenceLabel: TextView
    private lateinit var timing: SeekBar
    private lateinit var timingLabel: TextView
    private lateinit var frameRate: SeekBar
    private lateinit var frameRateLabel: TextView
    private lateinit var fallbackSpeed: SeekBar
    private lateinit var fallbackSpeedLabel: TextView
    private lateinit var direction: Spinner
    private lateinit var observationOnly: CheckBox
    private lateinit var overlayEnabled: CheckBox
    private lateinit var profiles: Spinner
    private lateinit var calibration: CalibrationGuideView
    private lateinit var diagnostics: TextView
    private var profile = CalibrationProfile()
    private var loading = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CaptureService.ACTION_DIAGNOSTICS -> {
                    val fps = intent.getFloatExtra("fps", 0f)
                    val process = intent.getLongExtra("processingMs", 0L)
                    val capture = intent.getLongExtra("captureLatencyMs", 0L)
                    val confidence = intent.getFloatExtra("confidence", 0f)
                    val count = intent.getIntExtra("detections", 0)
                    diagnostics.text = "FPS %.1f   Process %d ms   Capture-to-detect %d ms\nCandidates %d   Confidence %.2f"
                        .format(fps, process, capture, count, confidence)
                }
                CaptureService.ACTION_STATUS -> diagnostics.text = intent.getStringExtra(CaptureService.EXTRA_STATUS) ?: ""
                AccessibilityInputService.ACTION_INPUT_RESULT -> {
                    diagnostics.text = "Last gesture: ${intent.getStringExtra(AccessibilityInputService.EXTRA_RESULT)}"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ProfileRepository(this)
        if (repository.names().isEmpty()) repository.save(CalibrationProfile())
        profile = repository.load()
        buildInterface()
        loadProfile(profile)
        refreshProfileList()
        val filter = IntentFilter().apply {
            addAction(CaptureService.ACTION_DIAGNOSTICS)
            addAction(CaptureService.ACTION_STATUS)
            addAction(AccessibilityInputService.ACTION_INPUT_RESULT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun buildInterface() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(0xfff4f6f5.toInt())
        }
        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)

        page.addView(label("RHYTHM AUTOPLAYER", 22f, 0xff172b2a.toInt()))
        page.addView(label("Capture and detection are local. Autoplay is opt-in.", 14f, 0xff586765.toInt()))
        page.addView(section("Session"))
        page.addView(row(
            actionButton("Observe", 0xff126e64.toInt()) { beginCapture(autoplay = false) },
            actionButton("Autoplay", 0xffb44e2c.toInt()) { beginCapture(autoplay = true) }
        ))
        page.addView(actionButton("Stop capture", 0xff364744.toInt()) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        })
        page.addView(row(
            actionButton("Accessibility settings", 0xffe2e9e7.toInt()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            actionButton("Overlay permission", 0xffe2e9e7.toInt()) {
                if (Build.VERSION.SDK_INT >= 23) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        ))
        page.addView(actionButton("Test lane 1", 0xffe2e9e7.toInt()) {
            if (!AccessibilityInputService.dispatch(listOf(0), mapOf(0 to 120L), profile)) toast("Enable the accessibility input service first")
        })
        page.addView(diagnosticsLabel())

        page.addView(section("Calibration"))
        page.addView(label("Drag the horizontal line to set the receptor. Drag vertical guides to place lane centers.", 13f, 0xff586765.toInt()))
        calibration = CalibrationGuideView(this) { centers, hitY ->
            profile = profile.copy(laneCenters = centers, hitLineY = hitY).normalized()
            syncLabels()
        }
        page.addView(calibration, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)))

        laneLabel = label("Lane count", 15f, 0xff172b2a.toInt())
        page.addView(laneLabel)
        laneCount = seekBar(31) { value ->
            if (!loading) {
                val count = value + 1
                profile = profile.copy(laneCount = count, laneCenters = CalibrationProfile.evenlySpacedCenters(count))
                calibration.setGuides(profile.laneCenters, profile.hitLineY)
                syncLabels()
            }
        }.also { it.progress = 3 }
        page.addView(laneCount)
        hitLabel = label("Hit line", 15f, 0xff172b2a.toInt())
        page.addView(hitLabel)
        hitLine = seekBar(80) { value ->
            if (!loading) {
                profile = profile.copy(hitLineY = (value + 10) / 100f)
                calibration.setGuides(profile.laneCenters, profile.hitLineY)
                syncLabels()
            }
        }
        page.addView(hitLine)
        sensitivityLabel = label("Color sensitivity", 15f, 0xff172b2a.toInt())
        page.addView(sensitivityLabel)
        sensitivity = seekBar(78) { value ->
            if (!loading) {
                profile = profile.copy(sensitivity = (value + 2) / 100f)
                syncLabels()
            }
        }
        page.addView(sensitivity)
        confidenceLabel = label("Minimum detection confidence", 15f, 0xff172b2a.toInt())
        page.addView(confidenceLabel)
        confidence = seekBar(75) { value ->
            if (!loading) {
                profile = profile.copy(confidenceThreshold = (value + 20) / 100f)
                syncLabels()
            }
        }
        page.addView(confidence)
        timingLabel = label("Timing offset", 15f, 0xff172b2a.toInt())
        page.addView(timingLabel)
        timing = seekBar(400) { value ->
            if (!loading) {
                profile = profile.copy(timingOffsetMs = (value - 200).toLong())
                syncLabels()
            }
        }
        page.addView(timing)
        frameRateLabel = label("Frame processing interval", 15f, 0xff172b2a.toInt())
        page.addView(frameRateLabel)
        frameRate = seekBar(84) { value ->
            if (!loading) {
                profile = profile.copy(frameIntervalMs = (value + 16).toLong())
                syncLabels()
            }
        }
        page.addView(frameRate)
        fallbackSpeedLabel = label("Initial speed estimate", 15f, 0xff172b2a.toInt())
        page.addView(fallbackSpeedLabel)
        fallbackSpeed = seekBar(49) { value ->
            if (!loading) {
                profile = profile.copy(fallbackSpeedPxPerSecond = (100 + value * 100).toFloat())
                syncLabels()
            }
        }
        page.addView(fallbackSpeed)
        direction = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Notes move down", "Notes move up"))
            onItemSelectedListener = SimpleItemSelectedListener { index ->
                if (!loading) profile = profile.copy(direction = if (index == 0) ScrollDirection.DOWN else ScrollDirection.UP)
            }
        }
        page.addView(direction)
        observationOnly = CheckBox(this).apply {
            text = "Observation only (no input)"
            setOnCheckedChangeListener { _, checked -> if (!loading) profile = profile.copy(observationOnly = checked) }
        }
        page.addView(observationOnly)
        overlayEnabled = CheckBox(this).apply {
            text = "Show floating detection guides"
            setOnCheckedChangeListener { _, checked -> if (!loading) profile = profile.copy(overlayEnabled = checked) }
        }
        page.addView(overlayEnabled)

        page.addView(section("Profiles"))
        profileName = EditText(this).apply { hint = "Profile name"; isSingleLine = true }
        page.addView(profileName)
        profiles = Spinner(this)
        profiles.onItemSelectedListener = SimpleItemSelectedListener { index ->
            val name = repository.names().getOrNull(index) ?: return@SimpleItemSelectedListener
            if (!loading && name != profile.name) {
                repository.select(name)
                loadProfile(repository.load(name))
            }
        }
        page.addView(profiles)
        page.addView(row(
            actionButton("Save", 0xff126e64.toInt()) { saveProfile() },
            actionButton("Rename", 0xffe2e9e7.toInt()) {
                val name = profileName.text.toString().trim()
                repository.rename(profile.name, name)
                loadProfile(repository.load(name))
                refreshProfileList()
            },
            actionButton("Delete", 0xffe2e9e7.toInt()) {
                repository.delete(profile.name)
                val name = repository.selectedName()
                loadProfile(repository.load(name))
                refreshProfileList()
            }
        ))
    }

    private fun beginCapture(autoplay: Boolean) {
        if (autoplay && !AccessibilityInputService.isConnected()) {
            toast("Enable Rhythm Autoplayer in Accessibility settings, then test lane 1")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        profile = profile.copy(observationOnly = !autoplay)
        if (CaptureService.isCapturing) {
            repository.save(profile)
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_UPDATE_PROFILE))
            diagnostics.text = if (autoplay) "Autoplay enabled" else "Observation mode enabled"
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Activity result API kept dependency-free for this native starter")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            repository.save(profile)
            val service = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        } else if (requestCode == REQUEST_CAPTURE) {
            diagnostics.text = "Screen capture permission was not granted"
        }
    }

    private fun saveProfile() {
        val name = profileName.text.toString().trim().ifEmpty { "Default" }.take(40)
        profile = profile.copy(name = name).normalized()
        repository.save(profile)
        if (CaptureService.isCapturing) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_UPDATE_PROFILE))
        }
        refreshProfileList()
        toast("Profile saved")
    }

    private fun loadProfile(value: CalibrationProfile) {
        loading = true
        profile = value.normalized()
        profileName.setText(profile.name)
        laneCount.progress = profile.laneCount - 1
        hitLine.progress = (profile.hitLineY * 100).toInt() - 10
        sensitivity.progress = (profile.sensitivity * 100).toInt() - 2
        confidence.progress = (profile.confidenceThreshold * 100).toInt() - 20
        timing.progress = profile.timingOffsetMs.toInt() + 200
        frameRate.progress = profile.frameIntervalMs.toInt() - 16
        fallbackSpeed.progress = ((profile.fallbackSpeedPxPerSecond.toInt() - 100) / 100).coerceIn(0, 49)
        direction.setSelection(if (profile.direction == ScrollDirection.DOWN) 0 else 1)
        observationOnly.isChecked = profile.observationOnly
        overlayEnabled.isChecked = profile.overlayEnabled
        calibration.setGuides(profile.laneCenters, profile.hitLineY)
        syncLabels()
        loading = false
    }

    private fun refreshProfileList() {
        val names = repository.names().ifEmpty { listOf("Default") }
        @Suppress("UNCHECKED_CAST")
        (profiles.adapter as? ArrayAdapter<String>)?.let { adapter ->
            adapter.clear()
            adapter.addAll(names)
            adapter.notifyDataSetChanged()
        } ?: run {
            profiles.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        }
        val selected = names.indexOf(profile.name).coerceAtLeast(0)
        profiles.setSelection(selected)
    }

    private fun syncLabels() {
        laneLabel.text = "Lane count: ${profile.laneCount}"
        hitLabel.text = "Receptor line: ${(profile.hitLineY * 100).toInt()}%"
        sensitivityLabel.text = "Color sensitivity: ${(profile.sensitivity * 100).toInt()}%"
        confidenceLabel.text = "Minimum detection confidence: ${(profile.confidenceThreshold * 100).toInt()}%"
        timingLabel.text = "Timing offset: ${profile.timingOffsetMs} ms"
        frameRateLabel.text = "Frame processing interval: ${profile.frameIntervalMs} ms"
        fallbackSpeedLabel.text = "Initial speed estimate: ${profile.fallbackSpeedPxPerSecond.toInt()} px/s"
    }

    private fun diagnosticsLabel(): TextView = label("Waiting for capture", 13f, 0xff172b2a.toInt()).also {
        diagnostics = it
        it.setPadding(dp(0), dp(12), dp(0), dp(8))
    }

    private fun section(text: String) = label(text, 18f, 0xff126e64.toInt()).also { it.setPadding(0, dp(18), 0, dp(6)) }
    private fun label(text: String, size: Float, color: Int) = TextView(this).apply { this.text = text; textSize = size; setTextColor(color) }
    private fun seekBar(maximum: Int, changed: (Int) -> Unit) = SeekBar(this).apply {
        max = maximum
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = changed(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun actionButton(text: String, color: Int, action: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(if (color == 0xff126e64.toInt() || color == 0xffb44e2c.toInt()) 0xffffffff.toInt() else 0xff172b2a.toInt())
        setBackgroundColor(color)
        setOnClickListener { action() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { view -> addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CAPTURE = 80
        private const val REQUEST_NOTIFICATIONS = 81
    }
}