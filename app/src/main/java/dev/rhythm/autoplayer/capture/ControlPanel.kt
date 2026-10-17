package dev.rhythm.autoplayer.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

enum class AutoMode { EDIT, WATCH, PLAY }
enum class GuideLayer { WATCH_GUIDES, INPUT_TARGETS }

/** Small always-touchable floating bar: mode switch, lane count, test tap, close. */
class ControlPanel(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onMode(mode: AutoMode)
        fun onGuideLayer(layer: GuideLayer)
        fun onTest()
        fun onAddLane()
        fun onRemoveLane()
        fun onEven()
        fun onLinkRows(enabled: Boolean)
        fun onClose()
    }

    private val density = context.resources.displayMetrics.density
    private val root = LinearLayout(context)
    private val modeChips = HashMap<AutoMode, TextView>()
    private val guideChips = HashMap<GuideLayer, TextView>()
    private val editOnly = ArrayList<View>()
    private val laneText = chip("4")
    private val linkChip = chip("Row: on")
    private val statusText = TextView(context)
    private var linkRows = true
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(6), dp(6), dp(6), dp(6))
        root.background = rounded(0xE6101C1B.toInt())

        val top = row()
        val handle = chip("Move")
        top.addView(handle)
        for (mode in AutoMode.values()) {
            val c = chip(when (mode) {
                AutoMode.EDIT -> "Edit"
                AutoMode.WATCH -> "Watch"
                AutoMode.PLAY -> "Play"
            })
            c.setOnClickListener { listener.onMode(mode) }
            modeChips[mode] = c
            top.addView(c)
        }
        val close = chip("X")
        close.setOnClickListener { listener.onClose() }
        top.addView(close)

        val guideRow = row()
        for (layer in GuideLayer.values()) {
            val c = chip(if (layer == GuideLayer.WATCH_GUIDES) "Watch lanes" else "Tap targets")
            c.setOnClickListener { listener.onGuideLayer(layer) }
            guideChips[layer] = c
            guideRow.addView(c)
            editOnly.add(c)
        }

        val tools = row()
        val minus = chip("-")
        minus.setOnClickListener { listener.onRemoveLane() }
        val plus = chip("+")
        plus.setOnClickListener { listener.onAddLane() }
        val even = chip("Even")
        even.setOnClickListener { listener.onEven() }
        linkChip.setOnClickListener {
            linkRows = !linkRows
            linkChip.text = if (linkRows) "Row: on" else "Row: off"
            listener.onLinkRows(linkRows)
        }
        val test = chip("Test")
        test.setOnClickListener { listener.onTest() }
        tools.addView(minus)
        tools.addView(laneText)
        tools.addView(plus)
        tools.addView(even)
        tools.addView(linkChip)
        tools.addView(test)
        editOnly.addAll(listOf(minus, laneText, plus, even, linkChip))

        statusText.textSize = 11f
        statusText.setTextColor(0xFFCFE8E4.toInt())
        statusText.maxLines = 3
        statusText.maxWidth = dp(320)
        statusText.setPadding(dp(4), dp(2), dp(4), 0)
        statusText.text = "Edit watch lanes and tap targets separately"

        root.addView(top)
        root.addView(guideRow)
        root.addView(tools)
        root.addView(statusText)

        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, event ->
            val p = params
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p?.x ?: 0
                    startY = p?.y ?: 0
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> if (p != null) {
                    p.x = startX + (event.rawX - downX).toInt()
                    p.y = startY + (event.rawY - downY).toInt()
                    runCatching { windowManager?.updateViewLayout(root, p) }
                }
            }
            true
        }
        showMode(AutoMode.EDIT)
    }

    fun attach(manager: WindowManager) {
        windowManager = manager
        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(8)
        p.y = dp(8)
        params = p
        manager.addView(root, p)
    }

    fun detach() {
        runCatching { windowManager?.removeView(root) }
        windowManager = null
        params = null
    }

    fun showMode(mode: AutoMode) {
        for ((m, chip) in modeChips) {
            val color = when {
                m != mode -> 0xFF2B4A47.toInt()
                m == AutoMode.PLAY -> 0xFFC0502B.toInt()
                else -> 0xFF1FA593.toInt()
            }
            chip.background = rounded(color)
        }
        val visibility = if (mode == AutoMode.EDIT) View.VISIBLE else View.GONE
        editOnly.forEach { it.visibility = visibility }
    }

    fun showGuideLayer(layer: GuideLayer) {
        for ((item, chip) in guideChips) {
            chip.background = rounded(if (item == layer) 0xff74d84d.toInt() else 0xff2b4a47.toInt())
            chip.setTextColor(if (item == layer) 0xff10220f.toInt() else Color.WHITE)
        }
    }

    fun setLaneCount(count: Int) {
        laneText.text = count.toString()
    }

    fun setStatus(text: String) {
        statusText.text = text
    }

    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private fun chip(label: String): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        minWidth = dp(40)
        background = rounded(0xFF2B4A47.toInt())
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 10f * density
    }

    private fun dp(value: Int) = (value * density).toInt()
}
