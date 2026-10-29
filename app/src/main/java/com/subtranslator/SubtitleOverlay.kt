package com.subtranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.subtranslator.databinding.ItemSubtitleLineBinding
import com.subtranslator.databinding.OverlaySubtitleBinding
import kotlin.math.abs

/**
 * Floating subtitle panel drawn above other apps. Finished sentences stay as separate lines that
 * can be scrolled back; the sentence currently being spoken is the last line. The panel can be
 * collapsed to a single line showing only the latest subtitle.
 */
class SubtitleOverlay(
    context: Context,
    private val prefs: Prefs,
    private val listener: Listener,
) {
    interface Listener {
        fun onStopRequested()
        fun onSourceSelected(code: String)
        fun onTargetSelected(code: String)
    }

    private val context = ContextThemeWrapper(context, R.style.Theme_SubTranslator)
    private val wm = context.getSystemService(WindowManager::class.java)
    private val inflater = LayoutInflater.from(this.context)
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

    /** Latest subtitle text, shown in collapsed mode. */
    private var latest = ""
    private var status = ""
    private var liveHasContent = false

    init {
        binding.lines.addView(live.root)
        binding.scroll.onFollowChanged = { following ->
            binding.jumpToLatest.visibility =
                if (following || history.isEmpty()) View.GONE else View.VISIBLE
        }
        binding.jumpToLatest.setOnClickListener { binding.scroll.smoothScrollTo(0, Int.MAX_VALUE) }
        binding.collapseButton.setOnClickListener { setCollapsed(true) }
        binding.textSizeButton.setOnClickListener { cycleTextSize() }
        binding.languageChip.setOnClickListener { showLanguageMenu() }
        binding.stopButton.setOnClickListener { listener.onStopRequested() }
        attachDrag(binding.toolbar, onTap = null)
        attachDrag(binding.pill, onTap = { setCollapsed(false) })
        applyTextSize()
        updateLanguageChip()
        setCollapsed(prefs.overlayCollapsed)
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

    /** Grey placeholder shown while nothing is being said (e.g. "Listening…"). */
    fun setStatus(text: String) {
        status = text
        if (!liveHasContent) showStatus()
    }

    /** Sets the in-progress line (partial result). */
    fun setLive(original: String, translated: String) {
        fill(live, original, translated)
        liveHasContent = original.isNotEmpty() || translated.isNotEmpty()
        if (!liveHasContent) {
            showStatus()
        } else {
            live.translated.setTextColor(ContextCompat.getColor(context, R.color.overlay_translated))
            setLatest(translated.ifEmpty { original })
        }
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
        liveHasContent = false
        showStatus()
        setLatest(translated.ifEmpty { original })
        return line
    }

    fun updateLine(line: ItemSubtitleLineBinding, original: String, translated: String) {
        fill(line, original, translated)
        if (line === history.lastOrNull() && !liveHasContent) setLatest(translated)
    }

    fun updateLanguageChip() {
        val source = prefs.sourceLang.uppercase()
        val target = prefs.targetLang.uppercase()
        binding.languageChip.text = "$source → $target"
    }

    private fun showStatus() {
        // The status lives in the live line's translated view, styled grey. Once subtitles
        // exist they speak for themselves, so the placeholder is only shown before that.
        val status = if (history.isEmpty()) status else ""
        live.translated.text = status
        live.translated.setTextColor(ContextCompat.getColor(context, R.color.overlay_status))
        live.translated.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        live.original.visibility = View.GONE
        live.root.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        if (latest.isEmpty()) binding.pill.text = status
    }

    private fun setLatest(text: String) {
        if (text.isBlank()) return
        latest = text
        binding.pill.text = text
    }

    private fun fill(line: ItemSubtitleLineBinding, original: String, translated: String) {
        val showOriginal = prefs.showOriginal && original.isNotBlank() && original != translated
        line.original.text = original
        line.original.visibility = if (showOriginal) View.VISIBLE else View.GONE
        line.translated.text = translated
        line.translated.visibility = if (translated.isNotBlank()) View.VISIBLE else View.GONE
        line.root.visibility =
            if (translated.isBlank() && !showOriginal) View.GONE else View.VISIBLE
    }

    private fun setCollapsed(collapsed: Boolean) {
        prefs.overlayCollapsed = collapsed
        binding.box.visibility = if (collapsed) View.GONE else View.VISIBLE
        binding.pill.visibility = if (collapsed) View.VISIBLE else View.GONE
    }

    private fun cycleTextSize() {
        val next = TEXT_SIZES.firstOrNull { it > prefs.textSize } ?: TEXT_SIZES.first()
        prefs.textSize = next
        applyTextSize()
    }

    private fun applyTextSize() {
        (history + live).forEach(::styleLine)
        binding.pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSize.toFloat())
        binding.scroll.maxHeight = boxHeightPx()
    }

    private fun styleLine(line: ItemSubtitleLineBinding) {
        val size = prefs.textSize.toFloat()
        line.translated.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        line.original.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * ORIGINAL_SCALE)
    }

    private fun newLine() = ItemSubtitleLineBinding.inflate(inflater, binding.lines, false)
        .also(::styleLine)

    /** Approximate height of [Prefs.visibleLines] subtitle lines. */
    private fun boxHeightPx(): Int {
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, prefs.textSize.toFloat(), context.resources.displayMetrics
        )
        val perLine = sizePx * LINE_SPACING * (1 + if (prefs.showOriginal) ORIGINAL_SCALE else 0f)
        return (perLine * prefs.visibleLines).toInt()
    }

    private fun showLanguageMenu() {
        val models = ModelManager(context)
        val menu = PopupMenu(context, binding.languageChip)
        val listen = menu.menu.addSubMenu(R.string.source_language)
        // Only languages whose speech model is downloaded can be switched to on the fly.
        Languages.sources.filter { models.isVoskModelReady(it) }.forEach { lang ->
            listen.add(lang.label).setCheckable(true).setChecked(lang.code == prefs.sourceLang)
                .setOnMenuItemClickListener {
                    if (lang.code != prefs.sourceLang) listener.onSourceSelected(lang.code)
                    true
                }
        }
        val translate = menu.menu.addSubMenu(R.string.target_language)
        Languages.targets.forEach { lang ->
            translate.add(lang.label).setCheckable(true).setChecked(lang.code == prefs.targetLang)
                .setOnMenuItemClickListener {
                    if (lang.code != prefs.targetLang) listener.onTargetSelected(lang.code)
                    true
                }
        }
        menu.show()
    }

    /** Drags the window vertically; a touch that doesn't move counts as a tap. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(view: View, onTap: (() -> Unit)?) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var startY = 0
        var touchY = 0f
        var dragging = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    touchY = e.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - touchY
                    if (abs(dy) > slop) dragging = true
                    if (dragging) {
                        // Gravity is BOTTOM, so y grows upwards.
                        params.y = (startY - dy).toInt().coerceAtLeast(0)
                        if (attached) wm.updateViewLayout(binding.root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) prefs.overlayY = params.y else onTap?.invoke()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private companion object {
        const val MAX_HISTORY = 200
        const val OLD_LINE_ALPHA = 0.6f
        const val ORIGINAL_SCALE = 0.75f
        const val LINE_SPACING = 1.45f
        val TEXT_SIZES = listOf(14, 16, 18, 22, 26, 30)
    }
}
