package com.subtranslator

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** ScrollView that wraps its content but never grows taller than [maxHeight]. */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    var maxHeight = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    /** While true, the view keeps the newest (bottom) line visible as content grows. */
    var followBottom = true
        private set

    /** Called when [followBottom] changes because the user scrolled. */
    var onFollowChanged: ((Boolean) -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (followBottom) scrollToBottom()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val atBottom = isAtBottom()
        if (atBottom != followBottom) {
            followBottom = atBottom
            onFollowChanged?.invoke(atBottom)
        }
    }

    fun scrollToBottom() {
        val child = getChildAt(0) ?: return
        scrollTo(0, maxOf(0, child.height + paddingBottom - height))
    }

    /** True when the last line is visible (within a small tolerance). */
    fun isAtBottom(): Boolean {
        val child = getChildAt(0) ?: return true
        return scrollY + height >= child.height - paddingBottom - BOTTOM_SLOP_PX
    }

    private companion object {
        const val BOTTOM_SLOP_PX = 24
    }
}
