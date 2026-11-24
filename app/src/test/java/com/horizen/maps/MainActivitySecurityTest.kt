package com.horizen.maps

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySecurityTest {
    @Test
    fun acceptsOnlyTrustedHttpsOrigin() {
        assertTrue(isTrustedMapUri(Uri.parse("https://maps.horizensearch.com")))
        assertTrue(isTrustedMapUri(Uri.parse("HTTPS://MAPS.HORIZENSEARCH.COM:443/path")))
    }

    @Test
    fun rejectsUntrustedSchemesAndHosts() {
        assertFalse(isTrustedMapUri(Uri.parse("http://maps.horizensearch.com")))
        assertFalse(isTrustedMapUri(Uri.parse("file:///maps.horizensearch.com")))
        assertFalse(isTrustedMapUri(Uri.parse("https://maps.horizensearch.com.evil.test")))
        assertFalse(isTrustedMapUri(Uri.parse("https://evil.test")))
    }

    @Test
    fun rejectsCredentialAndUnexpectedPortUrls() {
        assertFalse(isTrustedMapUri(Uri.parse("https://maps.horizensearch.com@evil.test")))
        assertFalse(isTrustedMapUri(Uri.parse("https://user:pass@maps.horizensearch.com")))
        assertFalse(isTrustedMapUri(Uri.parse("https://maps.horizensearch.com:8443")))
    }
}
