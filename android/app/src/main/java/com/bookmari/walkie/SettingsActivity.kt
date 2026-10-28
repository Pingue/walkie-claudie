package com.bookmari.walkie

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** One LCD menu: PC address and key, the talk-button switches, voice, button test. Changes restart a running walkie. */
class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(WalkieService.PREFS, MODE_PRIVATE) }
    private lateinit var menu: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = Body(this) {}
        if (WalkieService.instance != null) body.led(Ui.LED_OK)
        menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.lcd(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(menu) }, weight = 1f)
        body.key(key(R.string.back) { finish() })
    }

    override fun onResume() {
        super.onResume()
        build() // the voice may have changed in VoiceActivity
    }

    private fun build() {
        menu.removeAllViews()
        val url = prefs.getString("url", "")!!
        val key = prefs.getString("key", "")!!

        title(R.string.sec_connection)
        row(R.string.pc_address, getString(R.string.tap_to_edit, url.removePrefix("http://").ifEmpty { getString(R.string.none) }), action(R.string.paste) { paste() }) {
            edit(R.string.pc_address, url, "http://192.168.0.10:7700", InputType.TYPE_TEXT_VARIATION_URI) { pasted(it) { saveUrl(it) } }
        }
        row(R.string.key, getString(R.string.tap_to_edit, getString(if (key.isEmpty()) R.string.none else R.string.key_saved)), action(R.string.qr) { scan() }) {
            edit(R.string.key, key, getString(R.string.key_hint), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { pasted(it) { save { putString("key", it) } } }
        }

        title(R.string.sec_talk)
        switch(R.string.trig_volume, R.string.trig_volume_sub, WalkieService.VOLUME, true) { if (!a11yOn()) a11yGuide() }
        if (prefs.getBoolean(WalkieService.VOLUME, true) && !a11yOn())
            row(R.string.a11y_off, getString(R.string.a11y_off_sub), action(R.string.how)) { a11yGuide() }
        // The earphone button is taken back by long-pressing the floating button, so each line says what the other allows.
        val floating = prefs.getBoolean(WalkieService.FLOATING, true)
        val ear = prefs.getBoolean(WalkieService.EAR_SINGLE, true) || prefs.getBoolean(WalkieService.EAR_DOUBLE, false)
        switch(R.string.trig_ear_single, if (floating) R.string.trig_ear_single_sub else R.string.trig_ear_single_sub_nofloat, WalkieService.EAR_SINGLE, true)
        switch(R.string.trig_ear_double, R.string.trig_ear_double_sub, WalkieService.EAR_DOUBLE, false)
        switch(R.string.trig_floating, if (ear) R.string.trig_floating_sub else R.string.trig_floating_sub_noear, WalkieService.FLOATING, true)

        title(R.string.sec_mic)
        val mic = prefs.getString(Mic.PREF, Mic.AUTO)!!
        row(R.string.mic, getString(R.string.mic_sub), action(Mic.label(mic))) {
            val chosen = Mic.next(mic)
            if (chosen == Mic.HEADSET && Mic.needsBluetoothPermission(this)) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 0)
            save { putString(Mic.PREF, chosen) }
        }

        title(R.string.sec_music)
        val music = prefs.getString(MusicFocus.PREF, MusicFocus.PAUSE)!!
        row(R.string.music, getString(R.string.music_sub), action(MusicFocus.label(music))) { save { putString(MusicFocus.PREF, MusicFocus.next(music)) } }

        title(R.string.sec_channels)
        switch(R.string.scan, R.string.scan_sub, WalkieService.SCAN, true)
        if (prefs.getBoolean(WalkieService.SCAN, true)) switch(R.string.scan_follow, R.string.scan_follow_sub, WalkieService.FOLLOW, false)

        title(R.string.voice)
        val voice = prefs.getString(WalkieService.TTS_VOICE, "")!!
        row(if (voice.isEmpty()) getString(R.string.voice_auto_short) else prefs.getString(WalkieService.TTS_VOICE_LABEL, null) ?: getString(R.string.voice_picked),
            getString(R.string.voice_speed, prefs.getFloat(WalkieService.TTS_RATE, 1f)), action(R.string.change)) {
            startActivity(Intent(this, VoiceActivity::class.java))
        }

        title(R.string.sec_check)
        row(getString(R.string.mic_test), getString(R.string.mic_test_sub), action(R.string.open)) { startActivity(Intent(this, MicTestActivity::class.java)) }
        row(getString(R.string.button_test), getString(R.string.button_test_sub), action(R.string.open)) { startActivity(Intent(this, ButtonTestActivity::class.java)) }
        menu.addView(lcdText(11f).apply { text = getString(R.string.app_version, BuildConfig.BUILD); alpha = 0.5f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(10) })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) Toast.makeText(this, R.string.mic_bt_denied, Toast.LENGTH_LONG).show()
    }

    private fun title(label: Int) = menu.menuTitle(getString(label))

    private fun row(label: Int, sub: String?, right: TextView, onClick: () -> Unit) = row(getString(label), sub, right, onClick)

    private fun row(label: String, sub: String?, right: TextView, onClick: () -> Unit) = menu.menuRow(label, sub, right, onClick)

    private fun action(label: Int, onClick: (() -> Unit)? = null) = lcdAction(getString(label), onClick)

    private fun switch(label: Int, sub: Int, pref: String, default: Boolean, onEnable: () -> Unit = {}) {
        val on = prefs.getBoolean(pref, default)
        val chip = lcdText(13f).apply {
            text = if (on) "ON" else "OFF"
            setPadding(dpi(5), dpi(2), dpi(5), dpi(2))
            if (on) { setBackgroundColor(Ui.INK); setTextColor(Ui.LCD) }
            else { background = android.graphics.drawable.GradientDrawable().apply { setStroke(dpi(1), Ui.INK) }; alpha = 0.6f }
        }
        row(getString(label), if (sub == 0) null else getString(sub), chip) {
            save { putBoolean(pref, !on) }
            if (!on) onEnable()
        }
    }

    /** Pairing text ({"url":…,"key":…}, same as the QR) fills both fields, whichever box it was pasted into. */
    private fun setPairing(text: String): Boolean {
        val p = Pairing.parse(text) ?: return false
        save { putString("url", p.url).putString("key", p.key) }
        return true
    }

    /** Text typed or pasted into a connection field: a pairing fills both, a broken one (it has a brace) is refused, anything else is handled by [otherwise]. */
    private fun pasted(text: String, otherwise: () -> Unit) {
        if (setPairing(text)) return
        if ('{' in text) return Toast.makeText(this, R.string.bad_pairing, Toast.LENGTH_LONG).show()
        otherwise()
    }

    private fun paste() {
        val text = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) return Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
        pasted(text) { saveUrl(text) }
    }

    private fun scan() = GmsBarcodeScanning.getClient(this).startScan().addOnSuccessListener { code ->
        if (!setPairing(code.rawValue.orEmpty())) Toast.makeText(this, R.string.bad_qr, Toast.LENGTH_LONG).show()
    }

    private fun saveUrl(url: String) = save { putString("url", Pairing.normalizeUrl(url)) }

    private fun edit(label: Int, value: String, hint: String, type: Int, onSave: (String) -> Unit) {
        val field = EditText(this).apply { setText(value); this.hint = hint; inputType = type; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(LinearLayout(this).apply { setPadding(dpi(20), dpi(8), dpi(20), 0); addView(field, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton(android.R.string.ok) { _, _ -> onSave(field.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Store, redraw, and restart a running walkie so it picks the change up. */
    private fun save(change: android.content.SharedPreferences.Editor.() -> Unit): Boolean {
        prefs.edit().apply(change).apply()
        build()
        if (WalkieService.instance != null) startWalkie()
        return true
    }
}
