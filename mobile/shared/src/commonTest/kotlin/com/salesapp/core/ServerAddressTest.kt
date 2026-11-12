package com.salesapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerAddressTest {
    private val live = "https://sales.example.com"

    @Test
    fun releaseIgnoresPlainHttpLeftFromTesting() {
        assertEquals(live, usableApiUrl("http://192.168.1.10:3000", live, debugBuild = false))
        assertEquals(live, usableApiUrl(null, live, debugBuild = false))
        assertEquals("https://other.example.com", usableApiUrl("https://other.example.com", live, debugBuild = false))
    }

    @Test
    fun debugKeepsWhateverWasSaved() {
        assertEquals("http://192.168.1.10:3000", usableApiUrl("http://192.168.1.10:3000", live, debugBuild = true))
        assertEquals(live, usableApiUrl("", live, debugBuild = true))
    }

    @Test
    fun releaseOnlyAcceptsHttps() {
        assertTrue(serverAddressAllowed("https://sales.example.com", debugBuild = false))
        assertFalse(serverAddressAllowed("http://sales.example.com", debugBuild = false))
        assertTrue(serverAddressAllowed("http://192.168.1.10:3000", debugBuild = true))
        assertFalse(serverAddressAllowed("sales.example.com", debugBuild = true))
    }
}
