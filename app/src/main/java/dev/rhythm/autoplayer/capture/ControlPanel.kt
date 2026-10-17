package dev.rhythm.autoplayer.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

enum class AutoMode { EDIT, WATCH, PLAY }
enum class GuideLayer { WATCH_GUIDES, INPUT_TARGETS }

/**
 * Floating control bar. It can be dragged by the handle and collapsed to a small round bubble
 * (tap the bubble to expand, long-press it to pause autoplay). Watch/Play auto-minimize so the bar
 * never covers the game.
 */
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
    private val full = LinearLayout(context)
    private val bubble = TextView(context)
    private val modeChips = HashMap<AutoMode, TextView>()
    private val guideChips = HashMap<GuideLayer, TextView>()
    private val editOnly = ArrayList<View>()
    private val laneText = chip("4")
    private val linkChip = chip("Row ✓")
    private val statusText = TextView(context)
    private var linkRows = true
    private var currentMode = AutoMode.EDIT
    private var minimized = false
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    val isMinimized: Boolean get() = minimized

    init {
        root.orientation = LinearLayout.VERTICAL

        // ---- expanded card -----------------------------------------------------------------
        full.orientation = LinearLayout.VERTICAL
        full.setPadding(dp(8), dp(6), dp(8), dp(8))
        full.background = rounded(0xF2111A19.toInt(), 16f)

        val top = row()
        val handle = chip("✥").apply { minWidth = dp(34) }
        top.addView(handle)
        for (mode in AutoMode.values()) {
            val c = chip(when (mode) {
                AutoMode.EDIT -> "Edit"
                AutoMode.WATCH -> "Watch"
                AutoMode.PLAY -> "Play"
            })
            c.setOnClickListener {
                listener.onMode(mode)
                if (mode != AutoMode.EDIT) minimize()
            }
            modeChips[mode] = c
            top.addView(c)
        }
        val minimizeChip = chip("–").apply { minWidth = dp(34) }
        minimizeChip.setOnClickListener { minimize() }
        val close = chip("✕").apply { minWidth = dp(34) }
        close.setOnClickListener { listener.onClose() }
        top.addView(minimizeChip)
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
        val minus = chip("−").apply { minWidth = dp(34) }
        minus.setOnClickListener { listener.onRemoveLane() }
        val plus = chip("+").apply { minWidth = dp(34) }
        plus.setOnClickListener { listener.onAddLane() }
        val even = chip("Even")
        even.setOnClickListener { listener.onEven() }
        linkChip.setOnClickListener {
            linkRows = !linkRows
            linkChip.text = if (linkRows) "Row ✓" else "Row ✗"
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
        statusText.setTextColor(0xFFB9D6D1.toInt())
        statusText.maxLines = 3
        statusText.maxWidth = dp(300)
        statusText.setPadding(dp(4), dp(4), dp(4), 0)
        statusText.text = "Edit watch lanes and tap targets separately"

        full.addView(top)
        full.addView(guideRow)
        full.addView(tools)
        full.addView(statusText)

        // ---- collapsed bubble --------------------------------------------------------------
        bubble.gravity = Gravity.CENTER
        bubble.textSize = 20f
        bubble.setTextColor(Color.WHITE)
        bubble.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        bubble.alpha = 0.88f
        bubble.visibility = View.GONE

        root.addView(full)
        root.addView(bubble)

        handle.setOnTouchListener { _, event ->
            drag(event)
            true
        }

        var downAt = 0L
        var moved = false
        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.elapsedRealtime()
                    moved = false
                    drag(event)
                }
                MotionEvent.ACTION_MOVE -> if (drag(event)) moved = true
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (SystemClock.elapsedRealtime() - downAt > 600L) {
                            // Long press = quick pause without opening the panel.
                            if (currentMode == AutoMode.PLAY) listener.onMode(AutoMode.WATCH)
                        } else {
                            expand()
                        }
                    }
                }
            }
            true
        }
        showMode(AutoMode.EDIT)
    }

    private var dragStartX = 0
    private var dragStartY = 0
    private var dragDownX = 0f
    private var dragDownY = 0f

    /** Handles a drag gesture for the window; returns true once the finger has really moved. */
    private fun drag(event: MotionEvent): Boolean {
        val p = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = p.x
                dragStartY = p.y
                dragDownX = event.rawX
                dragDownY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragDownX
                val dy = event.rawY - dragDownY
                if (abs(dx) < dp(6) && abs(dy) < dp(6) && p.x == dragStartX && p.y == dragStartY) return false
                val metrics = context.resources.displayMetrics
                p.x = (dragStartX + dx.toInt()).coerceIn(0, (metrics.widthPixels - dp(48)).coerceAtLeast(0))
                p.y = (dragStartY + dy.toInt()).coerceIn(0, (metrics.heightPixels - dp(48)).coerceAtLeast(0))
                runCatching { windowManager?.updateViewLayout(root, p) }
                return true
            }
        }
        return false
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

    fun minimize() {
        if (minimized) return
        minimized = true
        full.visibility = View.GONE
        bubble.visibility = View.VISIBLE
        refreshBubble()
        relayout()
    }

    fun expand() {
        if (!minimized) return
        minimized = false
        bubble.visibility = View.GONE
        full.visibility = View.VISIBLE
        relayout()
    }

    private fun relayout() {
        val p = params ?: return
        runCatching { windowManager?.updateViewLayout(root, p) }
    }

    fun showMode(mode: AutoMode) {
        currentMode = mode
        for ((m, chip) in modeChips) {
            val color = when {
                m != mode -> 0xFF2B4A47.toInt()
                m == AutoMode.PLAY -> 0xFFD2562F.toInt()
                else -> 0xFF1FA593.toInt()
            }
            chip.background = rounded(color)
        }
        val visibility = if (mode == AutoMode.EDIT) View.VISIBLE else View.GONE
        editOnly.forEach { it.visibility = visibility }
        refreshBubble()
    }

    private fun refreshBubble() {
        val (glyph, color) = when (currentMode) {
            AutoMode.EDIT -> "✎" to 0xFF2D7FD3.toInt()
            AutoMode.WATCH -> "◉" to 0xFF1FA593.toInt()
            AutoMode.PLAY -> "▶" to 0xFFD2562F.toInt()
        }
        bubble.text = glyph
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), 0xCCFFFFFF.toInt())
        }
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
        setPadding(dp(10), dp(7), dp(10), dp(7))
        minWidth = dp(40)
        background = rounded(0xFF2B4A47.toInt())
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    }

    private fun rounded(color: Int, radiusDp: Float = 10f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * density
    }

    private fun dp(value: Int) = (value * density).toInt()
}
