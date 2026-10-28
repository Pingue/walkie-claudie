package com.bookmari.walkie

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

private const val TODO_OPEN = "todo_open"
private const val A11Y_ASKED = "a11y_asked"

/** Status, usage, open items and conversation on LCDs; [settings][on/off][talk] keys. Talking happens via WalkieService. */
class MainActivity : Activity() {
    private lateinit var body: Body
    private lateinit var state: TextView
    private lateinit var channel: TextView
    private lateinit var antenna: Antenna
    private lateinit var u5: TextView
    private lateinit var u7: TextView
    private lateinit var c5: Cells
    private lateinit var c7: Cells
    private lateinit var todo: View
    private lateinit var todoHead: TextView
    private lateinit var todoList: TextView
    private lateinit var log: ScrollView
    private lateinit var logBox: View
    private lateinit var toBottom: TextView
    private lateinit var talk: TextView
    private lateinit var rec: View
    private lateinit var recHint: TextView
    private lateinit var heard: TextView
    private lateinit var power: TextView
    private lateinit var offMark: TextView
    private lateinit var chList: View
    private lateinit var chMenu: LinearLayout
    private lateinit var back: TextView
    private val mainKeys = ArrayList<View>()
    private var picking = false // the channel list is up
    private val cursor by lazy { CursorSpan(this) }
    private var shownText = ""
    private var pinned = true
    private var unseen = false // lines arrived while scrolled up
    private var lineCount = -1
    private val prefs by lazy { getSharedPreferences(WalkieService.PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        // No microphone yet (first run, or refused): say why before Android's own prompts.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestPermissions(perms, 0)
        else AlertDialog.Builder(this).setTitle(R.string.perm_title).setMessage(R.string.perm_guide).setCancelable(false)
            .setPositiveButton(R.string.next) { _, _ -> requestPermissions(perms, 0) }.show()
        body = Body(this, ::blink)

        // Top LCD: channel, connection, usage meters.
        state = lcdText(Ui.S).apply { alpha = 0.75f }
        antenna = Antenna(this)
        channel = lcdText().apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(channel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(state)
            addView(antenna, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(4) })
        }
        fun meter(): Pair<TextView, Cells> = lcdText() to Cells(this)
        val (t5, m5) = meter(); val (t7, m7) = meter()
        u5 = t5; u7 = t7; c5 = m5; c7 = m7
        val meters = LinearLayout(this).apply {
            listOf(u5 to c5, u7 to c7).forEachIndexed { i, (t, c) ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(t)
                    addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(3) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dpi(14) })
            }
        }
        // Tap: the channel list (other walkie sessions on the PC).
        body.lcd(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { openChannels() }
            addView(head)
            addView(meters, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4) })
        })

        // Open items; tap to fold down to the title line (remembered).
        todoHead = lcdText(Ui.S).apply { alpha = 0.75f }
        todoList = lcdText().apply { setLineSpacing(0f, 1.4f); wrapByWord() }
        todo = body.lcd(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(todoHead)
            addView(todoList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4) })
            setOnClickListener {
                prefs.edit().putBoolean(TODO_OPEN, !prefs.getBoolean(TODO_OPEN, true)).apply()
                render()
            }
        })

        // Conversation, newest at the bottom.
        // Not selectable: a selectable TextView relays out on every cursor blink, which fights scrolling.
        talk = lcdText().apply {
            setLineSpacing(0f, 1.4f)
            gravity = Gravity.BOTTOM
            wrapByWord()
        }
        log = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(talk)
        }
        // Scrolled up: an inverted chip bottom-right jumps back down; it says "new" if lines arrived meanwhile.
        toBottom = lcdText(Ui.S, Ui.LCD).apply {
            setBackgroundColor(Ui.INK)
            setPadding(dpi(10), dpi(8), dpi(10), dpi(8))
            visibility = View.GONE
            setOnClickListener { log.smoothScrollTo(0, talk.height) }
        }
        // Walkie off: the conversation dims under a big "Off" (on/off was hard to tell apart).
        offMark = lcdText(Ui.L).apply { setText(R.string.st_off); letterSpacing = 0.04f }
        logBox = body.lcd(android.widget.FrameLayout(this).apply {
            addView(log)
            addView(offMark, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(toBottom, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        }, weight = 1f)
        // Stay pinned to the newest line while the reader is at the bottom, even when the text grows
        // or the open-items LCD above changes size; scrolling up unpins.
        log.setOnScrollChangeListener { v, _, _, _, _ ->
            pinned = !v.canScrollVertically(1)
            if (pinned) unseen = false
            showToBottom()
        }
        val follow = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (pinned) log.post { log.scrollTo(0, talk.height) } }
        log.addOnLayoutChangeListener(follow)
        talk.addOnLayoutChangeListener(follow)

        // Transmitting: one inverted LCD in place of the two above.
        recHint = lcdText(Ui.S, Ui.LCD).apply { alpha = 0.8f }
        heard = lcdText(color = Ui.LCD).apply { gravity = Gravity.CENTER }
        rec = body.lcd(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(lcdText(Ui.L, Ui.LCD).apply { setText(R.string.transmitting); letterSpacing = 0.04f }, LinearLayout.LayoutParams(-2, -2))
            addView(recHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(10) })
            addView(heard, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(18) })
        }, inverted = true, weight = 1f)

        // Channel list: takes the place of the open-items and conversation LCDs, like transmitting does.
        chMenu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chList = body.lcd(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(chMenu) }, weight = 1f)

        body.key(key(R.string.settings) { startActivity(Intent(this, SettingsActivity::class.java)) }.apply { icon(PixelIcon.SLIDERS, R.string.settings) })
        power = key(R.string.power_off) {
            when {
                Talk.listening -> startService(Intent(this, WalkieService::class.java).setAction(WalkieService.CANCEL))
                WalkieService.instance != null -> { stopService(Intent(this, WalkieService::class.java)); buzz(off = true) }
                else -> {
                    startWalkie {
                        // Volume keys on but no accessibility: explain once; afterwards Settings shows it.
                        if (prefs.getBoolean(WalkieService.VOLUME, true) && !a11yOn() && !prefs.getBoolean(A11Y_ASKED, false)) {
                            prefs.edit().putBoolean(A11Y_ASKED, true).apply()
                            a11yGuide()
                        }
                    }
                    buzz(off = false)
                }
            }
        }
        body.key(power)
        body.key(key(R.string.talk) {
            startForegroundService(Intent(this, WalkieService::class.java).setAction(WalkieService.TALK))
        }.apply { icon(PixelIcon.MIC, R.string.talk) })
        for (i in 0 until body.keys.childCount) mainKeys.add(body.keys.getChildAt(i))
        back = key(R.string.back) { closeChannels() }
        body.key(back)
        (back.layoutParams as LinearLayout.LayoutParams).marginStart = 0 // shown alone, in place of the three
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        val mic = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (mic >= 0 && grantResults.getOrNull(mic) == PackageManager.PERMISSION_DENIED)
            AlertDialog.Builder(this).setTitle(R.string.mic_denied_title).setMessage(R.string.mic_denied)
                .setPositiveButton(R.string.app_info) { _, _ -> openAppInfo() }
                .setNegativeButton(R.string.later, null).show()
    }

    override fun onStart() {
        super.onStart()
        WalkieService.appShown = true
    }

    override fun onStop() {
        super.onStop()
        WalkieService.appShown = false
    }

    override fun onResume() {
        super.onResume()
        Talk.onChange = ::render
        render()
    }

    override fun onPause() {
        super.onPause()
        Talk.onChange = null
    }

    private fun openChannels() {
        if (Talk.listening) return
        picking = true
        fillChannels(checking = true) // last known list at once; the scan takes a second or two
        render()
        thread {
            Channels.scan(this)
            runOnUiThread { if (picking) fillChannels(checking = false) }
        }
    }

    private fun closeChannels() {
        picking = false
        render()
    }

    /** One row per channel seen so far: the current one marked, closed ones dimmed and not pickable. */
    private fun fillChannels(checking: Boolean) {
        chMenu.removeAllViews()
        chMenu.menuTitle(getString(R.string.channels))
        val now = Channels.port(this)
        val live = Channels.live.orEmpty()
        val ports = Channels.known(this)
        for (p in ports) {
            val open = p in live
            val sub = when {
                p == now -> getString(R.string.channel_now)
                checking -> getString(R.string.channel_checking)
                !open -> getString(R.string.channel_off)
                else -> null
            }
            val pick = if (checking && p != now || !checking && !open && p != now) null else ({
                if (p != now) Channels.switchTo(this, p)
                closeChannels()
            })
            chMenu.menuRow((if (p == now) "■ " else "□ ") + Channels.label(this, p), sub, null, pick)
            if (!checking && !open && p != now) chMenu.getChildAt(chMenu.childCount - 1).alpha = 0.4f
        }
        if (!checking && live.isEmpty())
            chMenu.addView(lcdText(Ui.S).apply { setText(R.string.channel_none); alpha = 0.7f; wrapByWord() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10) })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (picking) closeChannels() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun blink(on: Boolean) {
        if (Talk.status == R.string.st_connecting) antenna.bars = if (on) 1 else 0
        cursor.on = on
        talk.invalidate() // redraw only; no relayout
    }

    private fun render() {
        val running = WalkieService.instance != null
        val offline = Talk.status == R.string.st_offline
        state.setText(Talk.status)
        channel.text = Channels.label(this) + " ›"
        antenna.bars = if (Talk.status == R.string.st_connected) 4 else if (Talk.status == R.string.st_connecting) 1 else 0
        when {
            Talk.listening -> body.led(Ui.LED_REC)
            offline -> body.led(Ui.LED_REC, blink = true)
            running -> body.led(Ui.LED_OK)
            else -> body.led(Ui.KEY)
        }

        fun meter(t: TextView, c: Cells, label: Int, pct: Int) {
            t.text = SpannableStringBuilder(getString(label)).apply {
                setSpan(AbsoluteSizeSpan(Ui.S.toInt(), true), 0, length, 0)
                setSpan(ForegroundColorSpan(Ui.INK.alpha(0.75f)), 0, length, 0)
                append(" ").append(if (pct < 0) "--" else "$pct%")
            }
            c.lit = if (pct < 0) 0 else (pct + 5) / 10
            c.visibility = if (Talk.listening) View.GONE else View.VISIBLE
        }
        meter(u5, c5, R.string.usage_5h, Talk.usage5h)
        meter(u7, c7, R.string.usage_7d, Talk.usage7d)

        if (Talk.listening) picking = false
        todo.visibility = if (Talk.listening || picking) View.GONE else View.VISIBLE
        logBox.visibility = todo.visibility
        chList.visibility = if (picking) View.VISIBLE else View.GONE
        mainKeys.forEach { it.visibility = if (picking) View.GONE else View.VISIBLE }
        back.visibility = if (picking) View.VISIBLE else View.GONE
        rec.visibility = if (Talk.listening) View.VISIBLE else View.GONE
        recHint.text = getString(if (Talk.hold) R.string.release_to_send else R.string.press_to_send) +
            if (Talk.session > 1) " · " + getString(R.string.continued, Talk.session - 1) else ""
        heard.text = if (Talk.partial.isEmpty()) "" else "\"${Talk.partial}\""
        (power.background as KeyDrawable).led = if (running) Ui.LED_OK else Ui.LED_DARK
        offMark.visibility = if (running) View.GONE else View.VISIBLE
        log.alpha = if (running) 1f else 0.3f
        todoList.alpha = log.alpha
        if (Talk.listening) power.icon(PixelIcon.CANCEL, R.string.cancel)
        else power.icon(PixelIcon.POWER, if (running) R.string.power_off else R.string.power_on) // on/off: the key's LED

        val shown = Talk.issues.take(4)
        val open = prefs.getBoolean(TODO_OPEN, true)
        todoHead.text = (if (open) "▼ " else "▶ ") + getString(R.string.todo, Talk.issues.size)
        todoList.text = SpannableStringBuilder().apply {
            shown.forEach { para(todoList, "· ", it) }
            if (Talk.issues.size > shown.size) para(todoList, "", getString(R.string.todo_more, Talk.issues.size - shown.size))
        }
        todoList.visibility = if (shown.isEmpty() || !open) View.GONE else View.VISIBLE

        // Each exchange (my line + replies) starts with a gap; wrapped lines hang under the text, not the prefix.
        val sb = SpannableStringBuilder()
        val gap = dpi(14)
        for (l in Talk.lines.takeLast(100)) {
            if (l.mine) sb.para(talk, getString(R.string.me),
                l.text + if (l.state != Talk.State.ANSWERED) " (${getString(l.state.label)})" else "",
                ForegroundColorSpan(Ui.INK.alpha(0.6f)), gap = gap)
            else if (l.state == Talk.State.NOTE) sb.para(talk, "", "-- ${l.text} --",
                AbsoluteSizeSpan(Ui.S.toInt(), true), ForegroundColorSpan(Ui.INK.alpha(0.55f)), gap = gap)
            else sb.para(talk, getString(R.string.claude), l.text)
        }
        val sys = listOfNotNull(
            getString(R.string.lost).takeIf { offline },
            Talk.statusDetail.ifEmpty { null },
        )
        for (s in sys) sb.para(talk, "", s, AbsoluteSizeSpan(Ui.S.toInt(), true), ForegroundColorSpan(Ui.INK.alpha(0.55f)), gap = gap)
        sb.append("█")
        sb.setSpan(cursor, sb.length - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (sb.toString() == shownText) return // polls usually change nothing here; leave the view alone
        shownText = sb.toString()
        talk.text = sb
        if (!pinned && lineCount >= 0 && Talk.lines.size > lineCount) unseen = true
        lineCount = Talk.lines.size
        showToBottom()
    }

    private fun showToBottom() {
        toBottom.visibility = if (pinned) View.GONE else View.VISIBLE
        toBottom.setText(if (unseen) R.string.new_lines else R.string.to_bottom)
    }

    /** On: one buzz and the power-on chirp; off: two short buzzes and the power-off tone (on/off was hard to tell apart). */
    private fun buzz(off: Boolean) {
        getSystemService(android.os.Vibrator::class.java).vibrate(
            if (off) android.os.VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1)
            else android.os.VibrationEffect.createOneShot(90, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        android.media.MediaPlayer.create(this, if (off) R.raw.power_off else R.raw.power_on)
            ?.apply { setOnCompletionListener { it.release() }; start() }
    }

    /**
     * Appends a paragraph whose wrapped lines indent past [prefix], [gap] px below the one before.
     * The gap is a blank line sized to it: a LineHeightSpan gap spread to every wrapped line on a real phone.
     */
    private fun SpannableStringBuilder.para(view: TextView, prefix: String, text: String, vararg styles: Any, gap: Int = 0) {
        if (isNotEmpty()) {
            append("\n")
            if (gap > 0) { val s = length; append("\n"); setSpan(AbsoluteSizeSpan(gap * 2 / 3), s, length, 0) }
        }
        val start = length
        append(prefix).append(text)
        setSpan(LeadingMarginSpan.Standard(0, view.paint.measureText(prefix).toInt()), start, length, 0)
        for (s in styles) setSpan(s, start, length, 0)
    }
}

/**
 * (Re)starts the walkie with the stored settings, then [onStarted]. The floating button needs the overlay permission:
 * without it, explain and offer its settings screen or starting without the button.
 */
fun Activity.startWalkie(onStarted: () -> Unit = {}) {
    val prefs = getSharedPreferences(WalkieService.PREFS, Activity.MODE_PRIVATE)
    val start = {
        stopService(Intent(this, WalkieService::class.java)) // restart picks up new settings
        startForegroundService(Intent(this, WalkieService::class.java))
        onStarted()
    }
    if (prefs.getBoolean(WalkieService.FLOATING, true) && !Settings.canDrawOverlays(this))
        AlertDialog.Builder(this).setTitle(R.string.overlay_title).setMessage(R.string.overlay_guide)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton(R.string.overlay_skip) { _, _ -> start() }
            .show()
    else start()
}

/** Our accessibility service is on (the volume keys need it). */
fun Activity.a11yOn() =
    packageName in Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()

/** Why the volume keys need accessibility and how to turn it on, with both screens involved one tap away. */
fun Activity.a11yGuide() {
    AlertDialog.Builder(this).setTitle(R.string.a11y_guide_title).setMessage(R.string.a11y_guide)
        .setPositiveButton(R.string.a11y_open) { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        .setNeutralButton(R.string.app_info) { _, _ -> openAppInfo() }
        .setNegativeButton(R.string.later, null)
        .show()
}

fun Activity.openAppInfo() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
