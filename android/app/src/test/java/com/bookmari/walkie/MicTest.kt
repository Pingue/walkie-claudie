package com.bookmari.walkie

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicTest {
    private val builtin = AudioDeviceInfo.TYPE_BUILTIN_MIC
    private val wired = AudioDeviceInfo.TYPE_WIRED_HEADSET
    private val sco = AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    @Test fun autoLeavesRoutingToAndroid() = assertNull(Mic.pick(Mic.AUTO, listOf(builtin, wired)))

    @Test fun phonePicksBuiltinMicEvenWithHeadsetPlugged() = assertEquals(1, Mic.pick(Mic.PHONE, listOf(wired, builtin)))

    @Test fun headsetPicksFirstHeadsetInput() = assertEquals(1, Mic.pick(Mic.HEADSET, listOf(builtin, sco, wired)))

    @Test fun headsetWithoutHeadsetFallsBack() = assertNull(Mic.pick(Mic.HEADSET, listOf(builtin)))

    @Test fun phoneWithoutBuiltinMicFallsBack() = assertNull(Mic.pick(Mic.PHONE, emptyList()))

    @Test fun unknownPrefIsAuto() = assertNull(Mic.pick("bogus", listOf(builtin, wired)))

    @Test fun onlyBluetoothTypesAreFlaggedAsBluetooth() {
        assertEquals(true, Mic.isBluetooth(sco))
        assertEquals(true, Mic.isBluetooth(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(false, Mic.isBluetooth(wired))
        assertEquals(false, Mic.isBluetooth(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(false, Mic.isBluetooth(builtin))
    }

    @Test fun nextCyclesThroughAllChoices() {
        assertEquals(Mic.PHONE, Mic.next(Mic.AUTO))
        assertEquals(Mic.HEADSET, Mic.next(Mic.PHONE))
        assertEquals(Mic.AUTO, Mic.next(Mic.HEADSET))
        assertEquals(Mic.AUTO, Mic.next("bogus"))
    }
}
