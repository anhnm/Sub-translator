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
import com.subtranslator.databinding.OverlaySubtitleBinding

/** Floating, vertically draggable subtitle box drawn above other apps. */
class SubtitleOverlay(private val context: Context, private val prefs: Prefs) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val binding = OverlaySubtitleBinding.inflate(LayoutInflater.from(context))
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        val defaultY = (context.resources.displayMetrics.heightPixels * 0.12f).toInt()
        y = prefs.overlayY.takeIf { it >= 0 } ?: defaultY
    }
    private var attached = false

    fun show() {
        val size = prefs.textSize.toFloat()
        binding.translated.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        binding.original.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * 0.75f)
        val maxWidth = (context.resources.displayMetrics.widthPixels * 0.9f).toInt()
        binding.translated.maxWidth = maxWidth
        binding.original.maxWidth = maxWidth
        setupDrag()
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

    fun setText(original: String, translated: String) {
        val showOriginal = prefs.showOriginal && original.isNotBlank() && original != translated
        binding.original.text = original
        binding.original.visibility = if (showOriginal) View.VISIBLE else View.GONE
        binding.translated.text = translated
        binding.translated.visibility = if (translated.isNotBlank()) View.VISIBLE else View.GONE
        binding.root.visibility =
            if (translated.isBlank() && !showOriginal) View.GONE else View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        var startY = 0
        var touchY = 0f
        binding.root.setOnTouchListener { _, e ->
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
}
