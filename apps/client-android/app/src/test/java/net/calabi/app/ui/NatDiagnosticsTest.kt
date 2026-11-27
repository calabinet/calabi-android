package net.calabi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NatDiagnosticsTest {
    @Test
    fun masksPublicIpAddresses() {
        assertEquals("203.0.***.***", maskIpAddress("203.0.113.45"))
        assertEquals("240e:1234:****:****", maskIpAddress("240e:1234:5678::1"))
        assertEquals("", maskIpAddress(""))
    }

    @Test
    fun formatsPublicEndpointWithOptionalPortAndMask() {
        assertEquals("203.0.113.45:51820", formatPublicEndpoint("203.0.113.45", 51820, masked = false))
        assertEquals("203.0.113.45", formatPublicEndpoint("203.0.113.45", 0, masked = false))
        assertEquals("203.0.***.***", formatPublicEndpoint("203.0.113.45", 51820, masked = true))
        assertEquals("—", formatPublicEndpoint("", 51820, masked = false))
    }
}
