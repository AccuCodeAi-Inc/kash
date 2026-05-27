package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.tools.git.plumbing.PersonStamp

/**
 * Build a [PersonStamp] for git operations that need an author/committer
 * but don't have an explicit identity (`merge`, `rebase`, `stash`,
 * `cherry-pick`, `revert` synthesized commits).
 *
 * Pulls wall-clock seconds and the local tz offset from the machine's
 * [com.accucodeai.kash.api.clock.ShellClock] — the same source `date`,
 * `$EPOCHSECONDS`, and `git commit` consult — so conformance tests pin
 * a virtual clock and prod sees real wall time + local offset.
 *
 * Use this instead of hardcoded `PersonStamp("kash", "kash@localhost",
 * 1700000000, "+0000")` constants. Identity should be threaded from
 * config / env / adapter via [resolveIdentity] (in Commit.kt) where the
 * caller is the user-facing `git commit`; this helper is only for the
 * synthesized-merge/stash/etc. commits where there's no end-user input
 * to honor.
 */
internal fun nowPerson(
    ctx: CommandContext,
    name: String = "kash",
    email: String = "kash@localhost",
): PersonStamp {
    val clock = ctx.process.machine.clock
    return PersonStamp(name, email, clock.now().epochSeconds, clock.localTz())
}
