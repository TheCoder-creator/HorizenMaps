package com.horizen.maps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySecurityTest {
    @Test
    fun acceptsOnlyTrustedHttpsOrigin() {
        assertTrue(isTrustedMapUrl("https://maps.horizensearch.com"))
        assertTrue(isTrustedMapUrl("HTTPS://MAPS.HORIZENSEARCH.COM:443/path"))
    }

    @Test
    fun rejectsUntrustedSchemesAndHosts() {
        assertFalse(isTrustedMapUrl("http://maps.horizensearch.com"))
        assertFalse(isTrustedMapUrl("file:///maps.horizensearch.com"))
        assertFalse(isTrustedMapUrl("https://maps.horizensearch.com.evil.test"))
        assertFalse(isTrustedMapUrl("https://evil.test"))
    }

    @Test
    fun rejectsCredentialAndUnexpectedPortUrls() {
        assertFalse(isTrustedMapUrl("https://maps.horizensearch.com@evil.test"))
        assertFalse(isTrustedMapUrl("https://user:pass@maps.horizensearch.com"))
        assertFalse(isTrustedMapUrl("https://maps.horizensearch.com:8443"))
    }

    @Test
    fun rejectsMalformedUrls() {
        assertFalse(isTrustedMapUrl("https://maps.horizensearch.com:invalid"))
        assertFalse(isTrustedMapUrl("not a url"))
    }
}
