package com.bookmari.walkie.wear

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

object WatchPrefs {
    private const val NAME = "walkie"
    const val SWIPE = "swipe"

    /** Swipe to talk: taps and holds on the talk button are ignored, a swipe right toggles talking. */
    fun swipe(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(SWIPE, false)

    fun setSwipe(ctx: Context, on: Boolean) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(SWIPE, on).apply()
}

/** The watch's own settings: so far just the swipe-to-talk guard. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val toggle = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = 40 * dp; setColor(Color.parseColor("#3A4A3C")) }
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            setOnClickListener { WatchPrefs.setSwipe(context, !WatchPrefs.swipe(context)); render(this) }
        }
        render(toggle)
        val hint = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setText(R.string.swipe_hint)
            setPadding(0, (10 * dp).toInt(), 0, 0)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val side = (26 * dp).toInt() // the screen is round: keep text off the edge
            setPadding(side, side, side, side)
            addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    private fun render(v: TextView) {
        v.text = getString(R.string.swipe_title) + "\n" + getString(if (WatchPrefs.swipe(this)) R.string.swipe_on else R.string.swipe_off)
    }
}
