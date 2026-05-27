package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TildeExpansionTest {
    private suspend fun run(
        script: String,
        home: String = "/home/user",
    ): String =
        Kash(initialEnv = mapOf("HOME" to home, "PATH" to "/bin", "PWD" to "/tmp", "OLDPWD" to "/old"))
            .exec(script)
            .stdout

    @Test fun bareTildeExpandsToHome() =
        runTest {
            assertEquals("/home/user\n", run("echo ~"))
        }

    @Test fun tildeSlashPathExpands() =
        runTest {
            assertEquals("/home/user/bin\n", run("echo ~/bin"))
        }

    @Test fun quotedTildeIsLiteral() =
        runTest {
            assertEquals("~\n", run("echo \"~\""))
            assertEquals("~/bin\n", run("echo '~/bin'"))
        }

    @Test fun tildeFollowedByLetterIsUnknownUser() =
        runTest {
            // `~chet` is not a real user; bash leaves it literal.
            assertEquals("~chet\n", run("echo ~chet"))
            assertEquals("~chet/foo\n", run("echo ~chet/foo"))
        }

    @Test fun tildePlusIsPwd() =
        runTest {
            assertEquals("/tmp\n", run("echo ~+"))
        }

    @Test fun tildeMinusIsOldPwd() =
        runTest {
            assertEquals("/old\n", run("echo ~-"))
        }

    @Test fun tildeInAssignmentAfterColonExpands() =
        runTest {
            // bash: `PATH=/x:~/y` → `/x:/home/user/y`
            val r =
                Kash(initialEnv = mapOf("HOME" to "/home/user", "PATH" to "/x:/usr/bin", "PWD" to "/tmp"))
                    .exec($$"p=/x:~/y\necho \"$p\"")
            assertEquals("/x:/home/user/y\n", r.stdout)
        }

    @Test fun tildeLeadingInAssignmentExpands() =
        runTest {
            val r =
                Kash(initialEnv = mapOf("HOME" to "/h", "PATH" to "/bin", "PWD" to "/tmp"))
                    .exec($$"p=~/x\necho \"$p\"")
            assertEquals("/h/x\n", r.stdout)
        }

    @Test fun tildeAfterColonInWordIsLiteral() =
        runTest {
            // Outside assignment context, `:~` does NOT expand.
            assertEquals("abc:~/x\n", run("echo abc:~/x"))
        }
}
