package com.bookmari.walkie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Which microphone the walkie listens to: the phone's own, a headset's (wired, USB or Bluetooth), or whatever Android picks.
 * Monologue records through AudioRecord, which takes a preferred device directly. The talk-button recognizer does not
 * expose a device, so [open] nudges the system routing instead (API 31+) for the length of the recording.
 */
object Mic {
    const val PREF = "mic"
    const val AUTO = "auto"
    const val PHONE = "phone"
    const val HEADSET = "headset"
    val CHOICES = listOf(AUTO, PHONE, HEADSET)

    private val BLUETOOTH_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
    private val HEADSET_TYPES = BLUETOOTH_TYPES + setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)

    fun isBluetooth(type: Int) = type in BLUETOOTH_TYPES

    /** Android 12+ wants BLUETOOTH_CONNECT before the app may route audio to a Bluetooth device; wired and USB headsets need nothing. */
    fun needsBluetoothPermission(ctx: Context) = android.os.Build.VERSION.SDK_INT >= 31 &&
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

    /** Plain-language name of an input device type, for the mic test. */
    fun typeLabel(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> R.string.mic_dev_phone
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> R.string.mic_dev_wired
        AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.mic_dev_usb
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> R.string.mic_dev_bluetooth
        else -> R.string.mic_dev_other
    }

    fun describe(ctx: Context, device: AudioDeviceInfo?) =
        if (device == null) ctx.getString(R.string.mic_dev_unknown) else "${ctx.getString(typeLabel(device.type))} (${device.productName})"

    fun next(pref: String) = CHOICES[(CHOICES.indexOf(pref) + 1) % CHOICES.size]

    fun label(pref: String) = when (pref) {
        PHONE -> R.string.mic_phone
        HEADSET -> R.string.mic_headset
        else -> R.string.mic_auto
    }

    /** Index into [types] (device types of the available inputs) of the device [pref] asks for; null = let Android decide. */
    fun pick(pref: String, types: List<Int>): Int? = when (pref) {
        PHONE -> types.indexOf(AudioDeviceInfo.TYPE_BUILTIN_MIC).takeIf { it >= 0 }
        HEADSET -> types.indexOfFirst { it in HEADSET_TYPES }.takeIf { it >= 0 }
        else -> null
    }

    /** The input device for [pref], or null when it is "auto" or the asked-for kind is not connected right now. */
    fun device(ctx: Context, pref: String): AudioDeviceInfo? {
        val inputs = ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        return pick(pref, inputs.map { it.type })?.let { inputs[it] }
    }

    fun pref(ctx: Context) = ctx.getSharedPreferences(WalkieService.PREFS, Context.MODE_PRIVATE).getString(PREF, AUTO) ?: AUTO

    /** Route the recognizer to the chosen mic until [close]. Best effort: on failure the system default stays. */
    fun open(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val pref = pref(ctx)
        if (pref == AUTO) return
        val audio = ctx.getSystemService(AudioManager::class.java)
        val outputs = audio.availableCommunicationDevices
        // Headset: the headset itself. Phone: the built-in speaker, which makes the system fall back to the built-in mic.
        val target = when (pref) {
            HEADSET -> outputs.firstOrNull { it.type in HEADSET_TYPES }
            else -> outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } ?: return
        if (isBluetooth(target.type) && needsBluetoothPermission(ctx)) return // wired and USB headsets still work without it
        runCatching { audio.setCommunicationDevice(target) }
    }

    fun close(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        if (pref(ctx) == AUTO) return
        runCatching { ctx.getSystemService(AudioManager::class.java).clearCommunicationDevice() }
    }
}
