package dev.rhythm.autoplayer

import android.app.Activity
import android.content.Intent
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
import dev.rhythm.autoplayer.input.AccessibilityInputService

class MainActivity : Activity() {
    private lateinit var repository: ProfileRepository
    private lateinit var status: TextView
    private lateinit var profileName: EditText
    private lateinit var profileSpinner: Spinner
    private lateinit var markers: CheckBox
    private var profile = CalibrationProfile()
    private var suppressSpinner = false
    private val sliders = ArrayList<Slider>()

    /** A labelled SeekBar bound to one profile field. Only user drags write to the profile. */
    private inner class Slider(
        private val title: String,
        private val min: Int,
        private val max: Int,
        private val read: (CalibrationProfile) -> Int,
        private val format: (Int) -> String,
        private val write: (CalibrationProfile, Int) -> CalibrationProfile
    ) {
        val label = TextView(this@MainActivity).apply { textSize = 15f; setTextColor(0xff172b2a.toInt()) }
        val bar = SeekBar(this@MainActivity)

        init {
            bar.max = max - min
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "$title: ${format(progress + min)}"
                    if (fromUser) edit { write(it, progress + min) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        fun refresh() {
            val value = read(profile).coerceIn(min, max)
            bar.progress = value - min
            label.text = "$title: ${format(value)}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ProfileRepository(this)
        if (repository.names().isEmpty()) repository.save(CalibrationProfile())
        profile = repository.load()
        buildInterface()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        profile = repository.load()
        refreshAll()
    }

    private fun buildInterface() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(0xfff4f6f5.toInt())
        }
        setContentView(ScrollView(this).apply { addView(page) })

        page.addView(text("RHYTHM AUTOPLAYER", 22f, 0xff172b2a.toInt()))
        page.addView(text("Downscroll only. Everything stays on this phone.", 14f, 0xff586765.toInt()))

        page.addView(section("Setup"))
        status = text("", 13f, 0xff172b2a.toInt())
        page.addView(status)
        page.addView(row(
            button("1. Overlay permission", 0xffe2e9e7.toInt()) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            button("2. Accessibility", 0xffe2e9e7.toInt()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        ))
        page.addView(row(
            button("3. Start", 0xff126e64.toInt()) { beginSession() },
            button("Stop", 0xff364744.toInt()) {
                startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                refreshStatus()
            }
        ))
        page.addView(text(
            "Press Start, allow screen capture, then open your game. Use Watch lanes to align the " +
                "green scan columns with falling notes; use Tap targets to place the pink circles " +
                "where the game accepts touches. Check detection in Watch before Play.",
            13f, 0xff586765.toInt()
        ))

        page.addView(section("Keys"))
        val presets = row(*listOf(4, 6, 7, 9, 12, 21).map { count ->
            button("${count}K", 0xffe2e9e7.toInt()) {
                edit { it.withLaneCount(count) }
                refreshAll()
            }
        }.toTypedArray())
        page.addView(presets)
        addSlider(page, Slider("Number of buttons", 1, 32, { it.laneCount }, { "$it" }) { p, v -> p.withLaneCount(v) })
        addSlider(page, Slider("Button size", 2, 15, { Math.round(it.buttonSize * 100) }, { "$it%" }) { p, v ->
            p.copy(buttonSize = v / 100f)
        })

        page.addView(section("Detection and timing"))
        addSlider(page, Slider("Color sensitivity", 2, 80, { Math.round(it.sensitivity * 100) }, { "$it%" }) { p, v ->
            p.copy(sensitivity = v / 100f)
        })
        addSlider(page, Slider("Minimum confidence", 20, 95, { Math.round(it.confidenceThreshold * 100) }, { "$it%" }) { p, v ->
            p.copy(confidenceThreshold = v / 100f)
        })
        addSlider(page, Slider("Tap earlier (+) / later (-)", -200, 200, { it.timingOffsetMs.toInt() }, { "$it ms" }) { p, v ->
            p.copy(timingOffsetMs = v.toLong())
        })
        addSlider(page, Slider("Note speed guess", 1, 40, { Math.round(it.fallbackSpeed * 10) }, { "%.1f screens/s".format(it / 10f) }) { p, v ->
            p.copy(fallbackSpeed = v / 10f)
        })
        addSlider(page, Slider("Frame interval", 8, 100, { it.frameIntervalMs.toInt() }, { "$it ms" }) { p, v ->
            p.copy(frameIntervalMs = v.toLong())
        })
        markers = CheckBox(this).apply {
            text = "Show yellow detection dots in Watch/Play"
            setOnCheckedChangeListener { _, checked ->
                if (checked != profile.showMarkers) edit { it.copy(showMarkers = checked) }
            }
        }
        page.addView(markers)

        page.addView(section("Profiles"))
        profileName = EditText(this).apply { hint = "Profile name"; isSingleLine = true }
        page.addView(profileName)
        profileSpinner = Spinner(this)
        profileSpinner.onItemSelectedListener = SimpleItemSelectedListener { index ->
            if (suppressSpinner) return@SimpleItemSelectedListener
            val name = repository.names().getOrNull(index) ?: return@SimpleItemSelectedListener
            if (name != profile.name) {
                repository.select(name)
                profile = repository.load(name)
                notifyService()
                refreshAll()
            }
        }
        page.addView(profileSpinner)
        page.addView(row(
            button("Save as", 0xff126e64.toInt()) {
                val name = profileName.text.toString().trim().take(40)
                if (name.isEmpty()) {
                    toast("Type a profile name first")
                } else {
                    profile = profile.copy(name = name)
                    repository.save(profile)
                    notifyService()
                    refreshAll()
                    toast("Saved '$name'")
                }
            },
            button("Rename", 0xffe2e9e7.toInt()) {
                val name = profileName.text.toString().trim().take(40)
                if (name.isNotEmpty()) {
                    repository.rename(profile.name, name)
                    profile = repository.load(name)
                    notifyService()
                    refreshAll()
                }
            },
            button("Delete", 0xffe2e9e7.toInt()) {
                if (repository.names().size <= 1) {
                    toast("Keep at least one profile")
                } else {
                    repository.delete(profile.name)
                    profile = repository.load()
                    notifyService()
                    refreshAll()
                }
            }
        ))
    }

    private fun addSlider(page: LinearLayout, slider: Slider) {
        sliders.add(slider)
        page.addView(slider.label)
        page.addView(slider.bar)
    }

    private fun edit(change: (CalibrationProfile) -> CalibrationProfile) {
        profile = change(repository.load(profile.name)).normalized()
        repository.save(profile)
        notifyService()
    }

    private fun notifyService() {
        if (CaptureService.isRunning) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_UPDATE_PROFILE))
        }
    }

    private fun refreshAll() {
        sliders.forEach { it.refresh() }
        markers.isChecked = profile.showMarkers
        profileName.setText(profile.name)
        val names = repository.names().ifEmpty { listOf(profile.name) }
        suppressSpinner = true
        profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        profileSpinner.setSelection(names.indexOf(profile.name).coerceAtLeast(0))
        profileSpinner.post { suppressSpinner = false }
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "on" else "OFF"
        val access = if (AccessibilityInputService.isConnected()) "on" else "OFF"
        val session = if (CaptureService.isRunning) "running" else "stopped"
        status.text = "Overlay permission: $overlay\nAccessibility service: $access\nSession: $session"
    }

    private fun beginSession() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow 'Display over other apps' first")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (CaptureService.isRunning) {
            toast("Already running")
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Kept dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            if (!AccessibilityInputService.isConnected()) {
                toast("Remember to turn on the accessibility service")
            }
            moveTaskToBack(true)
        } else {
            toast("Screen capture permission was not granted")
        }
    }

    private fun section(title: String) = text(title, 18f, 0xff126e64.toInt()).also { it.setPadding(0, dp(18), 0, dp(6)) }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun button(label: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(if (color == 0xff126e64.toInt() || color == 0xff364744.toInt()) 0xffffffff.toInt() else 0xff172b2a.toInt())
        setBackgroundColor(color)
        setOnClickListener { action() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_CAPTURE = 80
    }
}
