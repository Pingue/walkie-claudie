package com.bookmari.walkie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    private val json = """{"url":"http://192.168.0.10:7700","key":"abc123"}"""

    @Test fun parsesTheQrText() {
        val p = Pairing.parse(json)!!
        assertEquals("http://192.168.0.10:7700", p.url)
        assertEquals("abc123", p.key)
    }

    @Test fun toleratesWhitespaceAndLineBreaks() {
        val p = Pairing.parse("\n  {\n \"url\": \"http://100.64.0.1:7700\",\n \"key\": \"k\"\n}  \n")!!
        assertEquals("http://100.64.0.1:7700", p.url)
        assertEquals("k", p.key)
    }

    @Test fun toleratesTextAroundTheJson() {
        val p = Pairing.parse("Or paste this into the app's address box:\n$json\n")!!
        assertEquals("abc123", p.key)
    }

    @Test fun addsHttpToABareAddress() = assertEquals("http://10.0.0.5:7700", Pairing.parse("""{"url":"10.0.0.5:7700","key":"k"}""")!!.url)

    @Test fun halfAPairingIsNotAPairing() {
        assertNull(Pairing.parse("""{"url":"http://10.0.0.5:7700"}"""))
        assertNull(Pairing.parse("""{"key":"k"}"""))
        assertNull(Pairing.parse("""{"url":"","key":"k"}"""))
        assertNull(Pairing.parse("""{"url":"http://10.0.0.5:7700","key":"  "}"""))
    }

    @Test fun otherTextIsNotAPairing() {
        assertNull(Pairing.parse(""))
        assertNull(Pairing.parse("http://192.168.0.10:7700"))
        assertNull(Pairing.parse("a1b2c3d4"))
        assertNull(Pairing.parse("{not json}"))
        assertNull(Pairing.parse("} {"))
    }

    @Test fun normalizeUrlLeavesFullUrlsAlone() {
        assertEquals("https://example.org", Pairing.normalizeUrl("https://example.org"))
        assertEquals("", Pairing.normalizeUrl(""))
    }
}
