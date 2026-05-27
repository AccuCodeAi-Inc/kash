package com.accucodeai.kash.api.user

/**
 * One entry in a [UserDatabase] — the kash analogue of a `passwd(5)` line
 * joined with the user's group memberships.
 *
 * @property groups primary group first; each pair is `(gid, name)`.
 */
public data class UserEntry(
    val name: String,
    val uid: Int,
    val gid: Int,
    val home: String,
    val groups: List<Pair<Int, String>>,
    val gecos: String = "",
    val shell: String = "/bin/sh",
)

/**
 * The shell's view of "who is running this." Backs POSIX `id`, `logname`,
 * `~user` tilde expansion, and the default values of `$LOGNAME`/`$USER`/`$HOME`.
 *
 * Implementations are pure code — like [com.accucodeai.kash.api.CommandRegistry],
 * they are NOT captured in snapshots and must be re-supplied at session restore.
 *
 * Real multi-user semantics (`su`, `setuid`, `/etc/passwd`) are out of scope;
 * kash is a single-user simulator. [current] is constant for the lifetime of
 * one session.
 */
public interface UserDatabase {
    /** The session's effective user. Stable across the session's lifetime. */
    public fun current(): UserEntry

    /** Look up by login name. `null` if unknown — `~bogus` should stay literal. */
    public fun lookup(name: String): UserEntry?

    /** Look up by numeric uid. `null` if unknown. */
    public fun lookupUid(uid: Int): UserEntry?

    public companion object {
        public val Default: UserDatabase =
            SingleUserDatabase(name = "user", uid = 1000, gid = 1000, home = "/home/user")
    }
}

/**
 * The default kash [UserDatabase] — exactly one user. All three lookup
 * methods return [current]'s entry when the argument matches; otherwise `null`.
 *
 * `extraGroups` are appended after the primary group, matching the POSIX
 * `id -G` ordering ("real or effective group ID first, then supplementary").
 */
public class SingleUserDatabase(
    name: String = "user",
    uid: Int = 1000,
    gid: Int = 1000,
    home: String = "/home/user",
    groupName: String = name,
    extraGroups: List<Pair<Int, String>> = emptyList(),
    gecos: String = "",
    shell: String = "/bin/sh",
) : UserDatabase {
    private val entry: UserEntry =
        UserEntry(
            name = name,
            uid = uid,
            gid = gid,
            home = home,
            groups = listOf(gid to groupName) + extraGroups,
            gecos = gecos,
            shell = shell,
        )

    override fun current(): UserEntry = entry

    override fun lookup(name: String): UserEntry? = if (name == entry.name) entry else null

    override fun lookupUid(uid: Int): UserEntry? = if (uid == entry.uid) entry else null
}
