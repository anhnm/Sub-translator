package com.subtranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.subtranslator.databinding.ItemSubtitleLineBinding
import com.subtranslator.databinding.OverlaySubtitleBinding

/**
 * Floating subtitle box drawn above other apps. Finished sentences stay as separate lines that
 * can be scrolled back; the sentence currently being spoken is the last line.
 */
class SubtitleOverlay(private val context: Context, private val prefs: Prefs) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val inflater = LayoutInflater.from(context)
    private val binding = OverlaySubtitleBinding.inflate(inflater)
    private val live = newLine()
    private val history = ArrayDeque<ItemSubtitleLineBinding>()
    private val params = WindowManager.LayoutParams(
        (context.resources.displayMetrics.widthPixels * 0.94f).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        val defaultY = (context.resources.displayMetrics.heightPixels * 0.08f).toInt()
        y = prefs.overlayY.takeIf { it >= 0 } ?: defaultY
    }
    private var attached = false

    init {
        binding.lines.addView(live.root)
        binding.scroll.maxHeight = boxHeightPx()
        binding.scroll.onFollowChanged = { following ->
            binding.jumpToLatest.visibility =
                if (following || history.isEmpty()) View.GONE else View.VISIBLE
        }
        binding.jumpToLatest.setOnClickListener { binding.scroll.smoothScrollTo(0, Int.MAX_VALUE) }
        setupDrag()
        updateVisibility()
    }

    fun show() {
        if (!attached) {
            wm.addView(binding.root, params)
            attached = true
        }
    }

    fun hide() {
        if (attached) {
            wm.removeView(binding.root)
            attached = false
        }
    }

    /** Sets the in-progress line (partial result or a status message). */
    fun setLive(original: String, translated: String) {
        fill(live, original, translated)
        updateVisibility()
    }

    /** Appends a finished sentence and returns a handle for updating its translation later. */
    fun addLine(original: String, translated: String): ItemSubtitleLineBinding {
        val line = newLine()
        fill(line, original, translated)
        history.lastOrNull()?.root?.alpha = OLD_LINE_ALPHA
        binding.lines.addView(line.root, binding.lines.childCount - 1)
        history.addLast(line)
        if (history.size > MAX_HISTORY) binding.lines.removeView(history.removeFirst().root)
        fill(live, "", "")
        updateVisibility()
        return line
    }

    fun updateLine(line: ItemSubtitleLineBinding, original: String, translated: String) =
        fill(line, original, translated)

    private fun fill(line: ItemSubtitleLineBinding, original: String, translated: String) {
        val showOriginal = prefs.showOriginal && original.isNotBlank() && original != translated
        line.original.text = original
        line.original.visibility = if (showOriginal) View.VISIBLE else View.GONE
        line.translated.text = translated
        line.translated.visibility = if (translated.isNotBlank()) View.VISIBLE else View.GONE
        line.root.visibility =
            if (translated.isBlank() && !showOriginal) View.GONE else View.VISIBLE
    }

    private fun updateVisibility() {
        val empty = history.isEmpty() && live.root.visibility == View.GONE
        binding.box.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun newLine(): ItemSubtitleLineBinding {
        val line = ItemSubtitleLineBinding.inflate(inflater, binding.lines, false)
        val size = prefs.textSize.toFloat()
        line.translated.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        line.original.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * ORIGINAL_SCALE)
        return line
    }

    /** Approximate height of [Prefs.visibleLines] subtitle lines. */
    private fun boxHeightPx(): Int {
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, prefs.textSize.toFloat(), context.resources.displayMetrics
        )
        val perLine = sizePx * LINE_SPACING * (1 + if (prefs.showOriginal) ORIGINAL_SCALE else 0f)
        return (perLine * prefs.visibleLines).toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        var startY = 0
        var touchY = 0f
        binding.handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Gravity is BOTTOM, so y grows upwards.
                    params.y = (startY - (e.rawY - touchY)).toInt().coerceAtLeast(0)
                    if (attached) wm.updateViewLayout(binding.root, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.overlayY = params.y
                    true
                }
                else -> false
            }
        }
    }

    private companion object {
        const val MAX_HISTORY = 200
        const val OLD_LINE_ALPHA = 0.7f
        const val ORIGINAL_SCALE = 0.75f
        const val LINE_SPACING = 1.45f
    }
}
