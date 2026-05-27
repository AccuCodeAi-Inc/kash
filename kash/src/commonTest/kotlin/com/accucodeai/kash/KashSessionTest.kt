package com.accucodeai.kash

import com.accucodeai.kash.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KashSessionTest {
    @Test fun sessionPreservesEnvAcrossExec() =
        runTest {
            val s = Kash(registry = standardRegistry()).newSession()
            s.exec("FOO=bar")
            val r = s.exec($$"echo $FOO")
            assertEquals("bar\n", r.stdout)
        }

    @Test fun sessionPreservesCwd() =
        runTest {
            val s = Kash(registry = standardRegistry()).newSession()
            s.exec("cd /tmp")
            assertEquals("/tmp", s.cwd)
            val r = s.exec("pwd")
            assertEquals("/tmp\n", r.stdout)
        }

    @Test fun sessionPreservesFunctions() =
        runTest {
            val s = Kash(registry = standardRegistry()).newSession()
            s.exec("hello() { echo hi; }")
            val r = s.exec("hello")
            assertEquals("hi\n", r.stdout)
        }
}
