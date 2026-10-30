package com.bookmari.walkie.wear

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable

/**
 * One big button: a tap is the phone's talk key (toggle), a long press starts or ends a monologue. The colour follows what the phone is doing.
 * A small strip above it picks the next channel, one below opens the watch settings. With "swipe to talk" on, the big button
 * ignores taps and holds and a swipe right toggles talking instead, so a brush of the wrist does nothing.
 */
class TalkActivity : Activity(), DataClient.OnDataChangedListener {
    // Same paths and states as the phone's WearLink.kt.
    private companion object {
        const val TALK = "/walkie/talk"
        const val MONOLOGUE = "/walkie/monologue"
        const val NEXT = "/walkie/next"
        const val STATE = "/walkie/state"
        const val OFF = "off"
        const val REC = "rec"
        val KEY = Color.parseColor("#3A4A3C")
        val LED_REC = Color.parseColor("#D8453A")
        const val SWIPE_MIN = 0.4f // of the button's width, to count as a swipe
    }

    private lateinit var button: TextView
    private var state: String? = OFF
    private var swipe = false
    private var downX = 0f
    private var downY = 0f
    private val face = GradientDrawable().apply { cornerRadius = 200f; setColor(KEY) } // as round as the room allows

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // a remote that sleeps mid-sentence is no use
        val dp = resources.displayMetrics.density
        button = TextView(this).apply {
            background = face
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(Color.WHITE)
            setOnClickListener { if (!swipe) send(it, TALK, HapticFeedbackConstants.CONTEXT_CLICK) }
            setOnLongClickListener { if (swipe) false else { send(it, MONOLOGUE, HapticFeedbackConstants.LONG_PRESS); true } } // true: no click on release
            setOnTouchListener { v, e -> swiped(v, e); false } // false: leave the click and long press to the view
        }
        // The strips are about half the width, so they clear the round bezel at the top and bottom.
        val stripWidth = (resources.displayMetrics.widthPixels * 0.5f).toInt()
        fun stripParams() = LinearLayout.LayoutParams(stripWidth, (34 * dp).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val side = (14 * dp).toInt() // keeps the button clear of a round bezel
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, (8 * dp).toInt(), side, (8 * dp).toInt())
            addView(strip(R.string.next_channel) { send(it, NEXT, HapticFeedbackConstants.CONTEXT_CLICK) }, stripParams())
            addView(button, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = (6 * dp).toInt(); bottomMargin = (6 * dp).toInt() })
            addView(strip(R.string.settings) { startActivity(Intent(this@TalkActivity, SettingsActivity::class.java)) }, stripParams())
        })
        show(OFF)
    }

    override fun onResume() {
        super.onResume()
        swipe = WatchPrefs.swipe(this)
        show(state) // the arrow on the label follows the setting
        val data = Wearable.getDataClient(this)
        data.addListener(this)
        data.getDataItems(Uri.Builder().scheme("wear").path(STATE).build()).addOnSuccessListener { items ->
            items.firstOrNull()?.let { show(DataMapItem.fromDataItem(it).dataMap.getString("state") ?: OFF) }
            items.release()
        }
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == STATE)
            show(DataMapItem.fromDataItem(e.dataItem).dataMap.getString("state") ?: OFF)
    }

    private fun strip(label: Int, onTap: (android.view.View) -> Unit) = TextView(this).apply {
        setText(label)
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = 100f; setColor(KEY) }
        setOnClickListener(onTap)
    }

    /** Swipe to talk: a mostly horizontal drag to the right across at least [SWIPE_MIN] of the button toggles talking. */
    private fun swiped(v: android.view.View, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                if (swipe && dx > v.width * SWIPE_MIN && abs(e.y - downY) < dx / 2) send(v, TALK, HapticFeedbackConstants.CONFIRM)
            }
        }
    }

    private fun send(v: android.view.View, path: String, haptic: Int) {
        v.performHapticFeedback(haptic)
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) return@addOnSuccessListener show(null)
            val messages = Wearable.getMessageClient(this)
            nodes.forEach { messages.sendMessage(it.id, path, ByteArray(0)) } // the paired phone, in practice the only node
        }.addOnFailureListener { show(null) }
    }

    /** [state] null = no phone connected. */
    private fun show(state: String?) {
        this.state = state
        face.setColor(if (state == REC) LED_REC else KEY)
        val label = getString(when (state) {
            null -> R.string.no_phone
            OFF -> R.string.off
            REC -> R.string.stop
            else -> R.string.talk
        })
        button.text = if (swipe && state != null && state != OFF) "$label ›" else label // the arrow says "swipe right"
        button.textSize = if (state == OFF || state == null) 14f else 22f
    }
}
