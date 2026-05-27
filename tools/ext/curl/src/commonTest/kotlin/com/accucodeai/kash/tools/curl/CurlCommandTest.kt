package com.accucodeai.kash.tools.curl

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private suspend fun runCurl(
    policy: NetworkPolicy? = NetworkPolicy.DenyAll,
    vararg args: String,
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = NullFs(),
            cwd = "/",
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = CurlCommand(policyOverride = policy).run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class CurlCommandTest {
    @Test fun noUrl_exitsTwo() =
        runTest {
            val (rc, _, err) = runCurl()
            assertEquals(2, rc)
            assertTrue(err.contains("no URL specified"), "err=$err")
        }

    @Test fun unsupportedFlag_exitsTwo() =
        runTest {
            val (rc, _, err) = runCurl(NetworkPolicy.DenyAll, "--multipart", "https://example.com")
            assertEquals(2, rc)
            assertTrue(err.contains("unsupported option"), "err=$err")
        }

    @Test fun denyAllPolicy_refusesRequest() =
        runTest {
            val (rc, _, err) = runCurl(NetworkPolicy.DenyAll, "https://example.com")
            assertEquals(7, rc)
            assertTrue(err.contains("network policy refused"), "err=$err")
        }

    @Test fun schemelessUrl_getsHttpPrefix() =
        runTest {
            // With DenyAll the request is still refused — but the error
            // message must show the http:// scheme we auto-applied, not
            // the `://:0` we'd get from a parse failure.
            val (rc, _, err) = runCurl(NetworkPolicy.DenyAll, "google.com")
            assertEquals(7, rc)
            assertTrue(err.contains("http://google.com"), "err=$err")
        }

    @Test fun malformedHeader_exitsTwo() =
        runTest {
            val (rc, _, err) = runCurl(NetworkPolicy.DenyAll, "-H", "no-colon-here", "https://x")
            assertEquals(2, rc)
            assertTrue(err.contains("malformed header"), "err=$err")
        }

    @Test fun shortFlagBundle_parsedOk() =
        runTest {
            // -sSL: silent + show-error + follow redirects. With DenyAll the
            // request still gets screened — but the bundle must parse.
            val (rc, _, err) = runCurl(NetworkPolicy.DenyAll, "-sSL", "https://example.com")
            assertEquals(7, rc)
            assertTrue(err.contains("network policy refused"), "err=$err")
        }
}

class CurlArgsTest {
    @Test fun parsesUrlAndMethod() {
        val opts = parseArgs(listOf("-X", "POST", "https://example.com/api"))
        assertEquals(listOf("https://example.com/api"), opts.urls)
        assertEquals("POST", opts.method)
    }

    @Test fun parsesDataAsBody() {
        val opts = parseArgs(listOf("-d", "hello=world", "https://x"))
        assertEquals("hello=world", opts.body?.decodeToString())
    }

    @Test fun parsesMultipleHeaders() {
        val opts =
            parseArgs(
                listOf(
                    "-H",
                    "Accept: application/json",
                    "-H",
                    "X-Token: abc",
                    "https://x",
                ),
            )
        assertEquals(
            listOf("Accept" to "application/json", "X-Token" to "abc"),
            opts.headers,
        )
    }

    @Test fun dashDashEndsOptions() {
        val opts = parseArgs(listOf("--", "-fL", "https://x"))
        assertEquals(listOf("-fL", "https://x"), opts.urls)
        assertEquals(false, opts.failOnError)
    }
}

class CurlPolicyTest {
    @Test fun allowlistAllowsExactHost() {
        val pol = NetworkPolicy.Allowlist(setOf("api.example.com"))
        assertTrue(pol.allows("api.example.com", 443, "https"))
        assertEquals(false, pol.allows("evil.com", 443, "https"))
    }

    @Test fun allowlistSubdomainPattern() {
        val pol = NetworkPolicy.Allowlist(setOf(".example.com"))
        assertTrue(pol.allows("api.example.com", 443, "https"))
        assertTrue(pol.allows("example.com", 443, "https"))
        assertEquals(false, pol.allows("evil.com", 443, "https"))
    }

    @Test fun allowlistPortFilter() {
        val pol = NetworkPolicy.Allowlist(setOf("*"), allowedPorts = setOf(443))
        assertTrue(pol.allows("anywhere.com", 443, "https"))
        assertEquals(false, pol.allows("anywhere.com", 80, "http"))
    }

    @Test fun deniedRaisesAccessDenied() {
        val pol = NetworkPolicy.DenyAll
        assertEquals(false, pol.allows("example.com", 443, "https"))
    }
}
