package com.accucodeai.kash.tools.git.http

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Lock down URL canonicalization. Users routinely paste GitHub web URLs
 * (PR pages, file viewers, tree URLs) into `git clone`; real git rejects
 * those, but a browser-based shell shouldn't punish "I copied this from
 * my browser tab" — we strip the web fragment and append `.git` for
 * hosts that need it.
 */
class CanonicalizeUrlTest {
    @Test fun stripsPullFragmentAndAppendsGit() {
        assertEquals(
            "https://github.com/acme/widget.git",
            canonicalizeBaseUrl("https://github.com/acme/widget/pull/42"),
        )
    }

    @Test fun stripsTreeAndBlobFragments() {
        assertEquals(
            "https://github.com/foo/bar.git",
            canonicalizeBaseUrl("https://github.com/foo/bar/tree/main/src"),
        )
        assertEquals(
            "https://github.com/foo/bar.git",
            canonicalizeBaseUrl("https://github.com/foo/bar/blob/main/README.md"),
        )
        assertEquals(
            "https://github.com/foo/bar.git",
            canonicalizeBaseUrl("https://github.com/foo/bar/commit/abc1234"),
        )
        assertEquals(
            "https://github.com/foo/bar.git",
            canonicalizeBaseUrl("https://github.com/foo/bar/issues/42"),
        )
    }

    @Test fun appendsGitForKnownHosts() {
        assertEquals(
            "https://github.com/a/b.git",
            canonicalizeBaseUrl("https://github.com/a/b"),
        )
        assertEquals(
            "https://github.com/a/b.git",
            canonicalizeBaseUrl("https://github.com/a/b/"),
        )
        assertEquals(
            "https://gitlab.com/a/b.git",
            canonicalizeBaseUrl("https://gitlab.com/a/b"),
        )
        assertEquals(
            "https://bitbucket.org/a/b.git",
            canonicalizeBaseUrl("https://bitbucket.org/a/b"),
        )
        assertEquals(
            "https://codeberg.org/a/b.git",
            canonicalizeBaseUrl("https://codeberg.org/a/b"),
        )
    }

    @Test fun leavesAlreadyDotGitUrlAlone() {
        assertEquals(
            "https://github.com/a/b.git",
            canonicalizeBaseUrl("https://github.com/a/b.git"),
        )
    }

    @Test fun leavesUnknownHostsAloneNoGitSuffix() {
        // Self-hosted gitea/forgejo servers vary — we don't try to guess.
        assertEquals(
            "https://git.example.com/a/b",
            canonicalizeBaseUrl("https://git.example.com/a/b"),
        )
    }
}
