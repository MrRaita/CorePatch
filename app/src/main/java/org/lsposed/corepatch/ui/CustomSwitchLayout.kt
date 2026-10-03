package org.lsposed.corepatch.ui

import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Switch
import android.widget.TextView
import org.lsposed.corepatch.R

class CustomSwitchLayout(context: Context) : CustomViewGroup(context) {

    val titleView = TextView(context).apply {
        setTextAppearance(android.R.style.TextAppearance_Medium)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        layoutParams = MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = 20.dp
            leftMargin = 20.dp
            rightMargin = 16.dp
        }
        this@CustomSwitchLayout.addView(this)
    }
    val subtitleView = TextView(context).apply {
        setTextColor(context.getColor(R.color.section_header))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        layoutParams = MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = 2.dp
            leftMargin = 20.dp
            bottomMargin = 20.dp
            rightMargin = 16.dp
        }
        this@CustomSwitchLayout.addView(this)
    }
    val switchView = Switch(context).apply {
        layoutParams = MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = 8.dp
            rightMargin = 20.dp
        }
        // Plain android.widget.Switch defaults to the old thin AOSP track/thumb look.
        // Swap in pill-shaped, colored drawables for a more Material appearance without
        // pulling in the AndroidX/Material Components dependency this project otherwise
        // avoids. trackDrawable/thumbDrawable setters exist since API 23 (minSdk is 28).
        trackDrawable = context.getDrawable(R.drawable.switch_track_selector)
        thumbDrawable = context.getDrawable(R.drawable.switch_thumb)
        // The stock Switch reserves extra horizontal space for its old text-on-track
        // labels ("ON"/"OFF"); we don't use those, so trim it for a tighter, modern look.
        showText = false
        this@CustomSwitchLayout.addView(this)
    }

    init {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        setBackgroundResource(outValue.resourceId)
        setOnClickListener { switchView.toggle() }
    }

    fun setOnCheckListener(listener: (Boolean) -> Unit) {
        switchView.setOnCheckedChangeListener { _, isChecked ->
            listener(isChecked)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        switchView.measure(
            measuredWidth.toAtMostMeasureSpec(), measuredHeight.toAtMostMeasureSpec()
        )
        val titleWidth =
            measuredWidth - switchView.measuredWidth - switchView.marginStart - switchView.marginEnd - titleView.marginStart - titleView.marginEnd

        titleView.measure(
            titleWidth.toExactlyMeasureSpec(), titleView.defaultHeightMeasureSpec(this)
        )
        subtitleView.measure(
            titleWidth.toExactlyMeasureSpec(), subtitleView.defaultHeightMeasureSpec(this)
        )

        val totalHeight =
            (titleView.marginTop + titleView.measuredHeight + subtitleView.measuredHeight + subtitleView.marginBottom).coerceAtLeast(
                switchView.measuredHeight
            )

        setMeasuredDimension(measuredWidth, totalHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val titleY = if (subtitleView.text.isNullOrEmpty()) {
            (height / 2) - (titleView.measuredHeight / 2)
        } else {
            titleView.marginTop
        }
        titleView.autoLayout(titleView.marginStart, titleY)
        subtitleView.autoLayout(titleView.marginStart, titleView.bottom)
        switchView.autoLayout(
            this@CustomSwitchLayout.measuredWidth - switchView.marginStart - switchView.measuredWidth,
            (height / 2) - (switchView.measuredHeight / 2)
        )
    }

}
