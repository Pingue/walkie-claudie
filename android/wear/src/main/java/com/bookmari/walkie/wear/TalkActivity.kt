package com.bookmari.walkie.wear

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable

/** One big button: a tap is the phone's talk key (toggle). The colour follows what the phone is doing. */
class TalkActivity : Activity(), DataClient.OnDataChangedListener {
    // Same paths and states as the phone's WearLink.kt.
    private companion object {
        const val TALK = "/walkie/talk"
        const val STATE = "/walkie/state"
        const val OFF = "off"
        const val REC = "rec"
        val KEY = Color.parseColor("#3A4A3C")
        val LED_REC = Color.parseColor("#D8453A")
    }

    private lateinit var button: TextView
    private val face = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(KEY) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // a remote that sleeps mid-sentence is no use
        button = TextView(this).apply {
            background = face
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(Color.WHITE)
            setOnClickListener { tap(it) }
        }
        val pad = (resources.displayMetrics.density * 14).toInt() // keeps the circle clear of a round bezel
        setContentView(FrameLayout(this).apply {
            setPadding(pad, pad, pad, pad)
            addView(button, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        show(OFF)
    }

    override fun onResume() {
        super.onResume()
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

    private fun tap(v: android.view.View) {
        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) return@addOnSuccessListener show(null)
            val messages = Wearable.getMessageClient(this)
            nodes.forEach { messages.sendMessage(it.id, TALK, ByteArray(0)) } // the paired phone, in practice the only node
        }.addOnFailureListener { show(null) }
    }

    /** [state] null = no phone connected. */
    private fun show(state: String?) {
        face.setColor(if (state == REC) LED_REC else KEY)
        button.setText(when (state) {
            null -> R.string.no_phone
            OFF -> R.string.off
            REC -> R.string.stop
            else -> R.string.talk
        })
        button.textSize = if (state == OFF || state == null) 14f else 22f
    }
}
