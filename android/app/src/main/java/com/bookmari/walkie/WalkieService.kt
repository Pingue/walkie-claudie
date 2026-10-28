package com.bookmari.walkie

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * The walkie-talkie: floating push-to-talk button, speech recognition, sending to the PC
 * channel, and long-polling for replies that are spoken aloud.
 * Start with action [TALK] to toggle listening (used by the in-app button), [CANCEL] to drop a recording.
 */
class WalkieService : Service() {
    companion object {
        const val TALK = "talk"
        const val CANCEL = "cancel"
        const val PREFS = "walkie"
        // Trigger switches (all on by default except earphone double-press).
        const val FLOATING = "trig_floating"
        const val MONOLOGUE_MS = 10 * 60_000L // auto-sends after this
        const val SCAN = "scan" // listen to every open channel
        const val FOLLOW = "scan_follow" // a reply from another channel makes it the active one
        const val VOLUME = "trig_volume"
        const val EAR_SINGLE = "trig_ear_single"
        const val EAR_DOUBLE = "trig_ear_double"
        // Voice: engine package ("" = phone default), voice name ("" = best), speech rate.
        const val TTS_ENGINE = "tts_engine"
        const val TTS_VOICE = "tts_voice"
        const val TTS_RATE = "tts_rate"
        const val TTS_VOICE_LABEL = "tts_voice_label" // "Voice 3 · online", for the settings line
        const val CHIMES_MUTED = "chimes_muted" // we muted the notification stream; undo on next start if the app died

        /** The running service, for the accessibility service's volume-key hold. Main thread only. */
        var instance: WalkieService? = null

        /** The app's own screen is up: it has its own talk key, so the floating one hides (10/12). Main thread only. */
        var appShown = false
            set(v) { field = v; instance?.showFloating() }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private val baseUrl get() = Channels.url(this) // the active channel can change under a running walkie (FOLLOW)
    private lateinit var key: String
    @Volatile private var running = true
    private val outbox = LinkedBlockingQueue<Talk.Line>()

    private lateinit var tts: TextToSpeech
    private lateinit var sounds: SoundPool // radio beeps from tools/sounds.py, on the media stream (earphones)
    private val soundIds = HashMap<Int, Int>()
    private var recognizer: SpeechRecognizer? = null
    private lateinit var wm: WindowManager
    private lateinit var button: TextView
    private lateinit var floating: FrameLayout
    private val led = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.LED_OK) }
    private var blinkOn = false
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("walkie", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "walkie")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentText(getString(R.string.notif_walkie))
            .setContentIntent(android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()
        // No microphone permission: Android refuses a microphone service outright, so start without it (replies still play).
        val mic = if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        startForeground(2, notification, mic or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        key = prefs.getString("key", "")!!
        unmuteChimes() // left muted if the app died mid-recording
        sounds = SoundPool.Builder().setMaxStreams(2)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        listOf(R.raw.talk_start, R.raw.sent, R.raw.received, R.raw.reply, R.raw.reply_other, R.raw.empty, R.raw.failed, R.raw.mono_start, R.raw.mono_end)
            .forEach { soundIds[it] = sounds.load(this, it, 1) }
        tts = openTts(this) {
            if (prefs.getBoolean(Channels.ANNOUNCE, false)) { // just switched channel
                prefs.edit().remove(Channels.ANNOUNCE).apply()
                speak(Channels.spoken(this), "channel")
            }
        }
        watchSpeech()
        setStatus(R.string.st_connecting)
        if (prefs.getBoolean(FLOATING, true) && Settings.canDrawOverlays(this)) addButton() // the in-app talk button works regardless
        addEarphone()
        instance = this
        // A call that starts mid-reply or mid-recording wins: stop both.
        if (android.os.Build.VERSION.SDK_INT >= 31) getSystemService(AudioManager::class.java)
            .addOnModeChangedListener(mainExecutor, modeListener)
        Talk.lines.filter { it.state == Talk.State.SENDING }.forEach(outbox::put) // unsent from last time
        thread(isDaemon = true, name = "walkie-send") { sendLoop() }
        thread(isDaemon = true, name = "walkie-poll") { pollLoop(Channels.port(this)) }
        thread(isDaemon = true, name = "walkie-scan") { scanLoop(prefs.getBoolean(SCAN, true)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TALK -> toggleListening()
            CANCEL -> { recognizer?.cancel(); endListening() }
        }
        return START_NOT_STICKY
    }

    // lazy: the listener type only exists on API 31+, so build it only behind the version checks.
    private val modeListener by lazy {
        AudioManager.OnModeChangedListener {
            // An active session keeps the earphone button even when its callback declines it, so a call's
            // answer/hang-up never reached the phone app (10/9, also while a reply was being read):
            // step aside for the whole call.
            KeyLog.add("walkie", "audio mode ${audio.mode}") // 1 ringing, 2 call, 3 VoIP; 0 normal
            session?.let { it.playing(!inCall()); it.isActive = !inCall() }
            if (inCall()) { stopSpeech(); recognizer?.cancel(); endListening(); mono?.let { monologue() } }
        }
    }

    override fun onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 31) getSystemService(AudioManager::class.java)
            .removeOnModeChangedListener(modeListener)
        instance = null
        running = false
        session?.release()
        recognizer?.destroy()
        unmuteChimes()
        if (::floating.isInitialized) wm.removeView(floating)
        mono?.stop()
        if (screenOn.isHeld) screenOn.release()
        tts.shutdown()
        focus.releaseAll()
        sounds.release()
        setStatus(R.string.st_off)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // --- floating button ---

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton() {
        wm = getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(
            -2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", 0)
            y = prefs.getInt("y", 600)
        }
        // A tiny walkie: the dark body, the rubber key, and the body's LED (same meaning as in the app).
        button = key(R.string.talk) {}.apply { icon(PixelIcon.MIC, R.string.talk, cell = 2f); isClickable = false }
        floating = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Ui.BODY); cornerRadius = dp(9) }
            addView(button, FrameLayout.LayoutParams(dpi(60), dpi(47)).apply { setMargins(dpi(8), dpi(14), dpi(8), dpi(8)) })
            addView(View(context).apply { background = led }, FrameLayout.LayoutParams(dpi(7), dpi(7), Gravity.TOP or Gravity.END).apply {
                topMargin = dpi(4); marginEnd = dpi(10)
            })
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        floating.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) + abs(dy) > 24) moved = true
                    if (moved) { lp.x = startX + dx; lp.y = startY + dy; wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> when {
                    moved -> prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    e.eventTime - e.downTime > 600 && session != null -> { // long-press: earphone button back to the walkie
                        playSilence()
                        beep(R.raw.received)
                    }
                    else -> toggleListening()
                }
            }
            // The key sinks while held, not while dragging (this listener took the key's own pressed state).
            button.isPressed = (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) && !moved
            true
        }
        wm.addView(floating, lp)
        showFloating()
        main.post(object : Runnable { // every 0.5 s, for the offline blink
            override fun run() {
                if (!running) return
                blinkOn = !blinkOn
                showLed()
                main.postDelayed(this, 500)
            }
        })
    }

    fun showFloating() {
        if (::floating.isInitialized) floating.visibility = if (appShown) View.GONE else View.VISIBLE
    }

    private fun showLed() = led.setColor(when {
        Talk.listening -> Ui.LED_REC
        Talk.status == R.string.st_offline -> if (blinkOn) Ui.LED_REC else Ui.KEY
        else -> Ui.LED_OK
    })

    private fun setListening(on: Boolean) {
        Talk.listening = on
        if (!on) { Talk.hold = false; Talk.partial = "" }
        Talk.refresh()
        if (::button.isInitialized) { (button.background as KeyDrawable).face = if (on) Ui.LED_REC else Ui.KEY; showLed() }
    }

    // --- earphone buttons ---

    /** Earphones send no hold time (measured on Bluetooth earbuds), so a press toggles talking. */
    private fun addEarphone() {
        val keys = buildSet {
            if (prefs.getBoolean(EAR_SINGLE, true)) addAll(listOf(
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
            ))
            if (prefs.getBoolean(EAR_DOUBLE, false)) add(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        if (keys.isEmpty()) return
        session = MediaSession(this, "walkie").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val e = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    KeyLog.key("walkie", e) // shows in the button test log: which presses still reach us during a call
                    if (e.keyCode !in keys || inCall()) return false // calls keep answer/hang-up
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) toggleListening()
                    return true
                }
            })
            playing(true)
            isActive = true
        }
        // Taken once at start. A music/podcast app that plays afterwards keeps the earphone button
        // (even paused; user's call 10/8) until the user takes it back: long-press the floating button.
        playSilence()
    }

    // --- speech in ---

    // A recording spans several recognizer sessions: the recognizer ends a session on its own when the
    // speaker pauses, so until the user ends it (release / press again) we keep what it heard and open a
    // fresh recognizer shortly after. [heard] is the text so far; [finishing] = the user ended it;
    // [errors] = recognizer error codes this recording, written to the conversation when it fails.
    private var heard = ""
    private var finishing = false
    private val errors = ArrayList<Int>()
    private var sessions = 0
    private var failures = 0 // in a row, without the recognizer getting ready
    private val maxRecording = Runnable { finishListening() } // a toggle someone forgot to press again
    // A recognizer that never answers the stop (seen right after the screen came on?): send what it heard so far.
    private val stopTimeout = Runnable {
        if (Talk.partial.length > heard.length) heard = Talk.partial
        doneListening()
    }

    // Google's recognizer chimes (usage NOTIFICATION_EVENT → notification stream, Galaxy phone 10/8) whenever a
    // session opens or closes, so a continued recording chimed at every pause, and the chimes doubled our own
    // beeps (and may play on the phone speaker too, being notifications). The notification stream is muted for
    // the whole recording and a moment after, so only our beeps play (media stream: the earphones). Music and
    // podcasts are on another stream. Already muted by the user: left alone. Muted when the app died: undone on start.
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val unmuteLater = Runnable { unmuteChimes() }

    private fun muteChimes() {
        main.removeCallbacks(unmuteLater)
        if (prefs.getBoolean(CHIMES_MUTED, false) || audio.isStreamMute(AudioManager.STREAM_NOTIFICATION)) return
        if (runCatching { audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0) }.isSuccess)
            prefs.edit().putBoolean(CHIMES_MUTED, true).apply()
    }

    private fun unmuteChimes() {
        main.removeCallbacks(unmuteLater)
        if (!prefs.getBoolean(CHIMES_MUTED, false)) return
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0) }
        prefs.edit().putBoolean(CHIMES_MUTED, false).apply()
    }

    private fun toggleListening() {
        if (mono != null) return monologue() // any talk button ends a monologue
        if (recognizer != null || Talk.listening) return finishListening()
        if (inCall()) return // the call owns the mic and the buttons
        focus.acquire("rec") // before stopSpeech, so the music doesn't get a moment of focus in between
        stopSpeech() // talking over a reply interrupts it
        heard = ""
        finishing = false
        errors.clear()
        sessions = 0
        failures = 0
        main.postDelayed(maxRecording, 120_000)
        setListening(true)
        muteChimes()
        Mic.open(this)
        session()
    }

    /** One recognizer session; a new recognizer each time (reusing one right after it ended failed on a real phone). */
    private fun session() {
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        val first = sessions++ == 0
        Talk.session = sessions
        Talk.refresh()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                failures = 0
                if (first) {
                    beep(R.raw.talk_start) // the next sessions stay silent
                }
            }
            override fun onResults(results: Bundle) {
                if (recognizer !== r) return
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) heard = "$heard $text".trim()
                if (finishing) doneListening() else next(r)
            }
            override fun onError(error: Int) {
                if (recognizer !== r) return
                errors.add(error)
                val pause = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
                // Busy/client/network errors right after a session (a real phone's recognizer closes slowly)
                // are retried with a growing pause; permission and language errors can't get better.
                val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                when {
                    finishing || fatal -> doneListening() // anything heard so far still goes out
                    pause -> next(r)
                    ++failures <= 5 -> next(r, 300L * failures)
                    else -> doneListening()
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                Talk.partial = "$heard $text".trim()
                Talk.refresh()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    /** The recognizer closed a session on a pause: drop it and open a fresh one shortly. */
    private fun next(r: SpeechRecognizer, delayMs: Long = 300) {
        r.destroy()
        recognizer = null
        main.postDelayed({ if (Talk.listening && recognizer == null && !finishing) session() }, delayMs)
    }

    /** The user ended the recording: the open session's result arrives in onResults/onError, then it sends. */
    private fun finishListening() {
        if (!Talk.listening) return
        finishing = true
        val r = recognizer ?: return doneListening() // between sessions: nothing pending
        r.stopListening()
        main.removeCallbacks(stopTimeout)
        main.postDelayed(stopTimeout, 3_000)
    }

    private fun doneListening() {
        val text = heard
        val failed = errors.filter { it != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && it != SpeechRecognizer.ERROR_NO_MATCH }
        endListening()
        // ponytail: diagnostic line while pause-continuation is being tuned on real phones; drop once it's stable.
        if (failed.isNotEmpty() || sessions > 1 || text.isEmpty() && errors.isNotEmpty())
            note(getString(R.string.err_speech_note, sessions, errors.joinToString(",").ifEmpty { "-" }))
        if (text.isEmpty()) beep(R.raw.empty) else send(text)
    }

    /** A line in the conversation for diagnosis (kept in the log, not sent). */
    private fun note(text: String) {
        Talk.lines.add(Talk.Line(UUID.randomUUID().toString(), text, Talk.State.NOTE))
        Talk.changed()
    }

    /** Picks up a voice changed in VoiceActivity. */
    fun reloadVoice() {
        tts.shutdown()
        speaking.clear()
        focus.release("tts")
        tts = openTts(this) {}
        watchSpeech()
    }

    /** Volume key held past the long-press threshold: start talking; [holdEnd] on release sends. */
    fun holdStart() {
        if (mono != null) return
        if (!Talk.listening) toggleListening()
        if (!Talk.listening) return // in a call
        Talk.hold = true
        Talk.refresh()
    }

    fun holdEnd() = finishListening()

    // --- monologue: volume-up hold starts and ends one long recording (Monologue.kt) ---

    private var mono: Monologue? = null
    @Suppress("DEPRECATION") // the only wake lock that keeps the screen on, which the volume keys need
    private val screenOn by lazy {
        getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK, "walkie:monologue")
    }
    private val maxMonologue = Runnable { mono?.let { monologue() } }

    fun monologue() {
        mono?.let {
            main.removeCallbacks(maxMonologue)
            beep(R.raw.mono_end)
            it.stop()
            return
        }
        if (android.os.Build.VERSION.SDK_INT < 33 || Talk.listening || inCall()) return beep(R.raw.empty)
        focus.acquire("rec")
        stopSpeech()
        muteChimes()
        beep(R.raw.mono_start)
        setListening(true)
        Mic.open(this)
        screenOn.acquire(MONOLOGUE_MS + 60_000)
        main.postDelayed(maxMonologue, MONOLOGUE_MS)
        mono = Monologue(this, { Talk.partial = it; Talk.refresh() }) { text ->
            mono = null
            Mic.close(this)
            focus.release("rec")
            if (screenOn.isHeld) screenOn.release()
            main.postDelayed(unmuteLater, 1_000)
            setListening(false)
            if (text.isEmpty()) beep(R.raw.empty) else send(text)
        }.also { it.start() }
    }

    private fun endListening() {
        main.removeCallbacks(maxRecording)
        main.removeCallbacks(stopTimeout)
        recognizer?.destroy()
        recognizer = null
        Mic.close(this)
        focus.release("rec")
        main.postDelayed(unmuteLater, 1_000) // the closing chime comes a little after
        setListening(false)
    }

    @Volatile private var sentBeepAt = 0L
    private val RECEIVED_GAP_MS = 1_200L // after the "sent" beep; 0.7 s still sounded run together on a real phone (10/9)

    private fun send(text: String) {
        val line = Talk.Line(UUID.randomUUID().toString(), text, Talk.State.SENDING)
        Talk.lines.add(line)
        Talk.changed()
        beep(R.raw.sent)
        sentBeepAt = android.os.SystemClock.uptimeMillis()
        outbox.put(line)
    }

    // --- network ---

    private fun http(method: String, path: String, body: String? = null, readTimeoutMs: Int = 10_000, base: String = baseUrl): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 5_000
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** Sends in order, retrying each message until the PC acks it; the server dedupes by id. */
    private fun sendLoop() {
        while (running) {
            val line = outbox.take()
            var firstFailure = 0L
            while (running) {
                try {
                    http("POST", "/say", JSONObject().put("id", line.id).put("text", line.text).toString())
                    main.post {
                        if (line.state == Talk.State.SENDING) line.state = Talk.State.RECEIVED
                        Talk.changed()
                    }
                    // On a LAN the ack is back within ~0.1 s: keep the "received" beep apart from the "sent" one.
                    main.postAtTime({ beep(R.raw.received) }, sentBeepAt + RECEIVED_GAP_MS)
                    break
                } catch (e: Exception) {
                    val now = System.currentTimeMillis()
                    if (firstFailure == 0L) firstFailure = now
                    // One "couldn't send" sound once it has been failing for a while; keep retrying silently.
                    else if (now - firstFailure in 30_000..33_000) main.post { beep(R.raw.failed) }
                    setStatus(R.string.st_offline, "${e.message} · ${getString(R.string.waiting, outbox.size + 1)}")
                    Thread.sleep(3_000)
                }
            }
        }
    }

    /** Scan (10/9): every open channel is listened to; other channels' replies play after a switch tone, and talking stays on the active channel. */
    private fun scanLoop(listenAll: Boolean) {
        val polling = mutableSetOf(Channels.port(this))
        while (running) {
            Channels.scan(this).filter { listenAll && polling.add(it) }.forEach { thread(isDaemon = true, name = "walkie-poll-$it") { pollLoop(it) } }
            main.post { Talk.refresh() } // names for the header
            Thread.sleep(60_000) // ponytail: sessions opened later are heard within a minute
        }
    }

    private fun pollLoop(port: Int) {
        val base = Channels.url(this, port)
        val cursorKey = Channels.cursorKey(this, port)
        var cursor = prefs.getLong(cursorKey, 0)
        // A channel never listened to may hold a long history: only its last 10 minutes are new to us.
        val since = if (prefs.contains(cursorKey)) 0L else System.currentTimeMillis() - 10 * 60_000
        var wait = "&wait=0" // first poll after (re)connecting answers at once
        while (running) {
            val active = port == Channels.port(this)
            try {
                val res = JSONObject(http("GET", "/poll?after=$cursor$wait", readTimeoutMs = 40_000, base = base))
                wait = ""
                val replies = res.getJSONArray("replies")
                for (i in 0 until replies.length()) {
                    val r = replies.getJSONObject(i)
                    cursor = maxOf(cursor, r.getLong("seq"))
                    if (runCatching { java.time.Instant.parse(r.getString("ts")).toEpochMilli() }.getOrDefault(Long.MAX_VALUE) < since) continue
                    val id = r.getLong("seq").toString()
                    if (active) main.post { onReply(id, r.getString("text"), r.optString("reply_to")) }
                    else main.post { onOtherReply(port, id, r.getString("text"), r.optString("reply_to")) }
                }
                prefs.edit().putLong(cursorKey, cursor).apply()
                if (!active) continue
                setStatus(R.string.st_connected)
                val issues = res.getJSONArray("issues").let { a -> List(a.length()) { a.getString(it) } }
                val usage = res.optJSONObject("usage")
                fun pct(k: String) = usage?.optJSONObject(k)?.optDouble("used_percentage")?.takeIf { !it.isNaN() }?.toInt() ?: -1
                val u5 = pct("five_hour"); val u7 = pct("seven_day")
                main.post { Talk.issues = issues; Talk.usage5h = u5; Talk.usage7d = u7; Talk.changed() }
            } catch (e: Exception) {
                if (!running) return
                if (active) setStatus(R.string.st_offline, e.message ?: "")
                wait = "&wait=0"
                Thread.sleep(if (active) 3_000 else 15_000)
            }
        }
    }

    /** Another channel's reply: kept in that channel's conversation, spoken after the switch tone. */
    private fun onOtherReply(port: Int, id: String, text: String, replyTo: String) {
        if (prefs.getBoolean(FOLLOW, false)) {
            Channels.follow(this, port)
            return onReply(id, text, replyTo, R.raw.reply_other)
        }
        val f = Channels.talkFile(this, port)
        val j = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject().put("lines", JSONArray()).put("issues", JSONArray()) }
        val a = j.getJSONArray("lines")
        for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.getString("id") == replyTo) it.put("state", Talk.State.ANSWERED.name) }
        a.put(JSONObject().put("id", id).put("text", text).put("state", Talk.State.REPLY.name))
        val tmp = File(f.path + ".tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(f)
        speakWhenFree(id, text, R.raw.reply_other)
    }

    private fun onReply(id: String, text: String, replyTo: String, chirp: Int = R.raw.reply) {
        Talk.lines.find { it.id == replyTo }?.state = Talk.State.ANSWERED
        Talk.lines.add(Talk.Line(id, text, Talk.State.REPLY))
        Talk.changed()
        speakWhenFree(id, text, chirp)
    }

    /** Replies wait out a phone call, then play with the incoming-radio chirp. */
    private fun speakWhenFree(id: String, text: String, chirp: Int = R.raw.reply) {
        if (!running) return
        if (inCall()) return run { main.postDelayed({ speakWhenFree(id, text, chirp) }, 2_000) }
        beep(chirp)
        main.postDelayed({ speak(text, id) }, 400)
    }

    // --- speech out: the music is held (paused or ducked) while any utterance is queued or playing ---

    private val focus by lazy {
        MusicFocus(MusicFocus.Android(this)) { prefs.getString(MusicFocus.PREF, MusicFocus.PAUSE)!! }
    }
    private val speaking = HashSet<String>() // utterance ids not finished yet; main thread only

    private fun speak(text: String, id: String) {
        speaking.add(id)
        focus.acquire("tts")
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finishedSpeaking(id) // no callbacks will come
    }

    private fun finishedSpeaking(id: String?) {
        if (id != null && speaking.remove(id) && speaking.isEmpty()) focus.release("tts")
    }

    private fun stopSpeech() {
        tts.stop()
        speaking.clear() // late callbacks for the flushed utterances no longer count
        focus.release("tts")
    }

    /** Gives the music back once the last queued utterance has finished (or failed). */
    private fun watchSpeech() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // Engine callbacks arrive on a binder thread.
            private fun finished(id: String?) { main.post { finishedSpeaking(id) } }
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
        })
    }

    private fun beep(res: Int) { sounds.play(soundIds[res] ?: return, 1f, 1f, 1, 0, 1f) }

    private fun setStatus(status: Int, detail: String = "") = main.post {
        Talk.status = status
        Talk.statusDetail = detail
        Talk.changed()
    }
}

/** Ringing, in a phone call, or in a VoIP call (KakaoTalk etc.): the walkie keeps its hands off. */
fun Context.inCall() = getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL

/**
 * "Playing" is what earns the earphone button, but Bluetooth earbuds see it too (AVRCP) and may send their
 * button as play/pause instead of answer/hang-up while it says playing — so a call sets it paused.
 */
fun MediaSession.playing(on: Boolean) = setPlaybackState(
    PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT)
        .setState(if (on) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, 1f)
        .build()
)
