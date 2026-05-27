package com.accucodeai.kash.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the redirect-time credential-scrub path that prevents
 * `Authorization` / `Cookie` from leaking to a host the user didn't
 * type. Mirrors real curl's post-CVE-2022-27776 behavior.
 */
class RedirectCredentialScrubTest {
    @Test fun sameOriginIdenticalUrls() {
        assertTrue(sameOrigin("https://api.example.com/a", "https://api.example.com/b"))
    }

    @Test fun sameOriginExplicitDefaultPort() {
        // http://x and http://x:80 are the same origin.
        assertTrue(sameOrigin("http://example.com/a", "http://example.com:80/b"))
        assertTrue(sameOrigin("https://example.com/a", "https://example.com:443/b"))
    }

    @Test fun differentHost() {
        assertFalse(sameOrigin("https://api.example.com/a", "https://evil.example/b"))
    }

    @Test fun differentScheme() {
        // http→https is treated as cross-origin: protocol downgrades and
        // upgrades both invalidate the credential carry.
        assertFalse(sameOrigin("http://example.com/a", "https://example.com/b"))
    }

    @Test fun differentPort() {
        assertFalse(sameOrigin("https://example.com:8443/a", "https://example.com/b"))
    }

    @Test fun unparseableLocationDropsCredentials() {
        // If we can't tell where the redirect points, default to "not same
        // origin" — i.e. strip the credentials. Conservative.
        assertFalse(sameOrigin("https://example.com/a", "this is not a url"))
    }

    @Test fun hostComparisonIsCaseInsensitive() {
        // RFC 3986 §3.2.2 — host is case-insensitive.
        assertTrue(sameOrigin("https://Example.Com/a", "https://example.com/b"))
    }

    @Test fun stripDropsAllThreeCredentialHeaders() {
        val before =
            listOf(
                "Authorization" to "Basic Zm9vOmJhcg==",
                "Cookie" to "session=abc",
                "Proxy-Authorization" to "Basic ...",
                "Accept" to "*/*",
                "User-Agent" to "curl-kash/1.0",
            )
        val after = stripCredentialHeaders(before)
        assertEquals(
            listOf("Accept" to "*/*", "User-Agent" to "curl-kash/1.0"),
            after,
        )
    }

    @Test fun stripIsCaseInsensitiveOnHeaderName() {
        // Header names are case-insensitive on the wire; user-supplied
        // mixed casing ("AUTHORIZATION") must still get scrubbed.
        val before =
            listOf(
                "AUTHORIZATION" to "Basic ...",
                "cookie" to "x=1",
                "X-Custom" to "keep",
            )
        assertEquals(listOf("X-Custom" to "keep"), stripCredentialHeaders(before))
    }

    @Test fun stripIsIdempotent() {
        val cleaned =
            listOf(
                "Accept" to "*/*",
                "User-Agent" to "curl-kash/1.0",
            )
        assertEquals(cleaned, stripCredentialHeaders(cleaned))
    }
}
