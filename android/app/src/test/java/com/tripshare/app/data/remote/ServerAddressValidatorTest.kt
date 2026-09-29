package com.tripshare.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressValidatorTest {
    @Test
    fun normalizesPrivateIpAndEmulatorWithoutSchemeToHttp() {
        assertEquals("http://192.168.1.20:3100/", ServerAddressValidator.normalize(" 192.168.1.20:3100 "))
        assertEquals("http://10.0.2.2:4173/", ServerAddressValidator.normalize("10.0.2.2:4173"))
        assertEquals("http://localhost:8080/", ServerAddressValidator.normalize("localhost:8080"))
    }

    @Test
    fun normalizesPublicHostAndIpToHttps() {
        assertEquals("https://trips.example.org/", ServerAddressValidator.normalize("trips.example.org"))
        assertEquals("https://8.8.8.8:8443/", ServerAddressValidator.normalize("8.8.8.8:8443"))
        assertEquals("https://trips.example.org:8443/", ServerAddressValidator.normalize("trips.example.org:8443"))
    }

    @Test
    fun permitsHttpsAndLocalHttpIncludingIpv6() {
        assertEquals("https://trips.example.org/", ServerAddressValidator.normalize("HTTPS://trips.example.org/"))
        assertEquals("http://[fd12:3456::1]:8080/", ServerAddressValidator.normalize("http://[fd12:3456::1]:8080"))
        assertEquals("https://[2001:4860:4860::8888]:8443/", ServerAddressValidator.normalize("https://[2001:4860:4860::8888]:8443"))
    }

    @Test
    fun rejectsPublicHttpAndNonRootUrls() {
        listOf(
            "http://trips.example.org",
            "http://8.8.8.8:8080",
            "https://trips.example.org/api/v1",
            "https://trips.example.org/?token=secret",
            "https://trips.example.org/#fragment",
            "https://user:password@trips.example.org"
        ).forEach { value ->
            assertRejected(value)
        }
    }

    @Test
    fun rejectsMalformedHostsPortsAndSchemes() {
        listOf(
            "https://256.1.1.1",
            "http://127.1",
            "192.168.1.2:0",
            "192.168.1.2:65536",
            "ftp://trips.example.org",
            "https://trips.example.org/path/",
            "https://trips.example.org\\@evil.example",
            "https://your-domain.example"
        ).forEach { value ->
            assertRejected(value)
        }
    }

    @Test
    fun sameOriginIncludesSchemeHostAndPort() {
        assertTrue(ServerAddressValidator.isSameOrigin("http://10.0.2.2:4173/", "http://10.0.2.2:4173/share/abc"))
        assertFalse(ServerAddressValidator.isSameOrigin("http://10.0.2.2:4173/", "https://10.0.2.2:4173/share/abc"))
        assertFalse(ServerAddressValidator.isSameOrigin("https://trips.example.org/", "https://evil.example.org/share/abc"))
    }

    @Test
    fun cleartextIsLimitedToLocalHosts() {
        assertTrue(ServerAddressValidator.allowsCleartextHost("10.0.2.2"))
        assertTrue(ServerAddressValidator.allowsCleartextHost("fd12:3456::1"))
        assertFalse(ServerAddressValidator.allowsCleartextHost("8.8.8.8"))
        assertFalse(ServerAddressValidator.allowsCleartextHost("share.example.org"))
    }

    private fun assertRejected(value: String) {
        val result = runCatching { ServerAddressValidator.normalize(value) }
        assertTrue("Expected rejection for $value, got ${result.getOrNull()}", result.isFailure)
    }
}
