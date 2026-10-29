package com.bookmari.walkie

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/** The paired watch's remote talk key. Paths and states are duplicated in the :wear module (TalkActivity). */
object WearLink {
    const val TALK = "/walkie/talk" // watch -> phone: same as pressing the talk key
    const val STATE = "/walkie/state" // phone -> watch: a data item, so a watch that opens later still sees it
    const val OFF = "off" // the walkie service is not running
    const val IDLE = "idle"
    const val REC = "rec"

    fun push(ctx: Context, state: String) {
        val req = PutDataMapRequest.create(STATE).apply {
            dataMap.putString("state", state)
            dataMap.putLong("t", System.currentTimeMillis()) // an unchanged map would not notify the watch
        }.asPutDataRequest().setUrgent()
        runCatching { Wearable.getDataClient(ctx).putDataItem(req) } // no Play services / no watch: nothing to tell
    }
}

class WearListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearLink.TALK) return
        // Android 14 refuses to start a microphone service from the background, so a tap only works while the walkie is on.
        Handler(Looper.getMainLooper()).post {
            WalkieService.instance?.talkToggle() ?: WearLink.push(this, WearLink.OFF)
        }
    }
}
