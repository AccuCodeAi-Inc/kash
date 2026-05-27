package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpecialVarsTest {
    @Test fun randomIsInjectableAndDeterministic() =
        runTest {
            val seeded = Random(42)
            val kash = Kash(randomSource = { seeded.nextInt(0, 32768) })
            val a = kash.exec("echo \$RANDOM").stdout.trim()
            val b = kash.exec("echo \$RANDOM").stdout.trim()
            // Two references → two distinct values from the seeded stream.
            assertNotEquals(a, b)
            // Reproducibility: a fresh Kash + the same seed yields the same first value.
            val replay = Kash(randomSource = { Random(42).nextInt(0, 32768) })
            assertEquals(Random(42).nextInt(0, 32768).toString(), replay.exec("echo \$RANDOM").stdout.trim())
        }

    @Test fun randomDefaultIsInRange() =
        runTest {
            val out = Kash().exec("echo \$RANDOM").stdout.trim()
            val n = out.toInt()
            assertTrue(n in 0..32767, "RANDOM out of range: $n")
        }

    @Test fun secondsIsNonNegativeInteger() =
        runTest {
            val out = Kash().exec("echo \$SECONDS").stdout.trim()
            assertTrue(out.toLong() >= 0L, "SECONDS not non-negative: $out")
        }

    @Test fun epochSecondsIsPlausible() =
        runTest {
            val out = Kash().exec("echo \$EPOCHSECONDS").stdout.trim()
            // Any year >= 2020 is plausible.
            assertTrue(out.toLong() > 1_577_836_800L, "EPOCHSECONDS implausible: $out")
        }

    @Test fun epochRealtimeHasMicros() =
        runTest {
            val out = Kash().exec("echo \$EPOCHREALTIME").stdout.trim()
            val parts = out.split(".")
            assertEquals(2, parts.size, "EPOCHREALTIME shape wrong: $out")
            assertEquals(6, parts[1].length, "EPOCHREALTIME microseconds not 6 digits: $out")
        }
}
