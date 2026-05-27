package com.accucodeai.kash.tools.posix.mktemp

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths
import kotlin.random.Random

/**
 * GNU-style `mktemp` — create a unique temporary file (or directory with
 * `-d`) inside the virtual filesystem and print its path.
 *
 * Template: a trailing run of `X`s (at least 3) is replaced by random
 * alphanumeric chars `[A-Za-z0-9]`. Default template if none supplied is
 * [DEFAULT_TEMPLATE] (`tmp.XXXXXXXXXX`).
 *
 * Supported options:
 *  - `-d` / `--directory` — create a directory instead of a regular file.
 *  - `-q` / `--quiet` — suppress diagnostics on failure.
 *  - `-u` / `--dry-run` — print a unique name without creating it.
 *  - `-p DIR` / `--tmpdir[=DIR]` — use DIR as the parent. Default is
 *    `$TMPDIR` then `/tmp`. With `--tmpdir` (no `=DIR`), the template is
 *    interpreted relative to `$TMPDIR`/`/tmp`.
 *  - `-t` — legacy: interpret TEMPLATE as a filename and place it under
 *    `$TMPDIR`/`/tmp`.
 *  - `--suffix=SUF` — append SUF after the random part. If using a custom
 *    template, the template must end with `X` (no other trailing chars) or
 *    this is an error.
 *
 * Exit 0 on success, 1 on failure (template error, no parent dir, retry
 * exhaustion, &c.). `-q` silences the stderr message but does not change
 * the exit code.
 */
public class MktempCommand(
    /**
     * Random source. Override in tests to make collision-retry deterministic.
     * Default [Random.Default] is fine — kash mktemp is for script logic,
     * not security.
     */
    private val random: Random = Random.Default,
) : Command,
    CommandSpec {
    override val name: String = "mktemp"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE, CommandTag.STATEFUL)
    override val command: Command get() = this

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var directory = false
        var quiet = false
        var dryRun = false
        var legacyT = false
        var tmpdir: String? = null
        var tmpdirOptSeen = false
        var suffix = ""
        val operands = mutableListOf<String>()
        var endOfOptions = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                endOfOptions -> {
                    operands += a
                }

                a == "--" -> {
                    endOfOptions = true
                }

                a == "-d" || a == "--directory" -> {
                    directory = true
                }

                a == "-q" || a == "--quiet" -> {
                    quiet = true
                }

                a == "-u" || a == "--dry-run" -> {
                    dryRun = true
                }

                a == "-t" -> {
                    legacyT = true
                }

                a == "-p" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("mktemp: option requires an argument -- 'p'\n")
                        return CommandResult(exitCode = 1)
                    }
                    tmpdir = args[i + 1]
                    tmpdirOptSeen = true
                    i++
                }

                a == "--tmpdir" -> {
                    tmpdirOptSeen = true
                }

                a.startsWith("--tmpdir=") -> {
                    tmpdir = a.removePrefix("--tmpdir=")
                    tmpdirOptSeen = true
                }

                a.startsWith("--suffix=") -> {
                    suffix = a.removePrefix("--suffix=")
                }

                a == "--suffix" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("mktemp: option requires an argument -- 'suffix'\n")
                        return CommandResult(exitCode = 1)
                    }
                    suffix = args[i + 1]
                    i++
                }

                a == "--help" -> {
                    ctx.stdout.writeUtf8(HELP_TEXT)
                    return CommandResult(exitCode = 0)
                }

                a.startsWith("--") -> {
                    if (!quiet) ctx.stderr.writeUtf8("mktemp: unknown option: $a\n")
                    return CommandResult(exitCode = 1)
                }

                a.startsWith("-") && a.length > 1 && a != "-" -> {
                    // Cluster of short flags.
                    for (ch in a.drop(1)) {
                        when (ch) {
                            'd' -> {
                                directory = true
                            }

                            'q' -> {
                                quiet = true
                            }

                            'u' -> {
                                dryRun = true
                            }

                            't' -> {
                                legacyT = true
                            }

                            else -> {
                                if (!quiet) ctx.stderr.writeUtf8("mktemp: invalid option -- '$ch'\n")
                                return CommandResult(exitCode = 1)
                            }
                        }
                    }
                }

                else -> {
                    operands += a
                }
            }
            i++
        }

        if (operands.size > 1) {
            if (!quiet) ctx.stderr.writeUtf8("mktemp: too many templates\n")
            return CommandResult(exitCode = 1)
        }

        val rawTemplate = operands.firstOrNull() ?: DEFAULT_TEMPLATE
        // POSIX requires trailing Xs (>= 3) but GNU accepts >= 3. We accept
        // any run of trailing Xs; below 3 is an error.
        val xRun = rawTemplate.takeLastWhile { it == 'X' }.length
        if (xRun < MIN_X_COUNT) {
            if (!quiet) {
                ctx.stderr.writeUtf8(
                    "mktemp: too few X's in template '$rawTemplate'\n",
                )
            }
            return CommandResult(exitCode = 1)
        }

        // Resolve the parent directory and the template's filename part.
        val tmpdirEnv = ctx.env["TMPDIR"]?.ifEmpty { null }
        val defaultTmpdir = tmpdirEnv ?: "/tmp"

        // GNU semantics: when `-t` or `--tmpdir`/`-p` is given, the template
        // is interpreted as a single filename component (no slashes), placed
        // under the tmpdir. Without those flags, the template is taken as-is
        // (relative to cwd) or as an absolute path.
        val parentDir: String
        val fileTemplate: String
        if (legacyT) {
            val tdir = tmpdir ?: defaultTmpdir
            if ('/' in rawTemplate) {
                if (!quiet) {
                    ctx.stderr.writeUtf8(
                        "mktemp: invalid template, '$rawTemplate', contains directory separator\n",
                    )
                }
                return CommandResult(exitCode = 1)
            }
            parentDir = Paths.resolve(ctx.process.cwd, tdir)
            fileTemplate = rawTemplate
        } else if (tmpdirOptSeen) {
            val tdir = tmpdir ?: defaultTmpdir
            if (rawTemplate.startsWith("/")) {
                // GNU mktemp errors if the template is absolute when --tmpdir given.
                if (!quiet) {
                    ctx.stderr.writeUtf8(
                        "mktemp: invalid template, '$rawTemplate'; with --tmpdir, it may not be absolute\n",
                    )
                }
                return CommandResult(exitCode = 1)
            }
            parentDir = Paths.resolve(ctx.process.cwd, tdir)
            fileTemplate = rawTemplate
        } else {
            // No -t/--tmpdir/-p — interpret template directly.
            val abs =
                if (rawTemplate.startsWith("/")) {
                    rawTemplate
                } else if (operands.isEmpty()) {
                    // Default template — root it at $TMPDIR/tmp.
                    "${defaultTmpdir.trimEnd('/')}/$rawTemplate"
                } else {
                    Paths.resolve(ctx.process.cwd, rawTemplate)
                }
            parentDir = Paths.parent(abs)
            fileTemplate = Paths.basename(abs)
        }

        // Verify the parent exists and is a directory (only if we'll create).
        if (!dryRun) {
            if (!ctx.process.fs.exists(parentDir)) {
                if (!quiet) {
                    ctx.stderr.writeUtf8(
                        "mktemp: failed to create file via template '$fileTemplate': " +
                            "No such file or directory: $parentDir\n",
                    )
                }
                return CommandResult(exitCode = 1)
            }
            if (!ctx.process.fs.isDirectory(parentDir)) {
                if (!quiet) {
                    ctx.stderr.writeUtf8(
                        "mktemp: failed to create via template '$fileTemplate': " +
                            "Not a directory: $parentDir\n",
                    )
                }
                return CommandResult(exitCode = 1)
            }
        }

        // Split fileTemplate into prefix + X-run.
        val templateXRun = fileTemplate.takeLastWhile { it == 'X' }.length
        val prefix = fileTemplate.dropLast(templateXRun)

        repeat(MAX_RETRIES) {
            val randPart = randomString(templateXRun)
            val candidate = "${parentDir.trimEnd('/').ifEmpty { "" }}/$prefix$randPart$suffix"
            val candidatePath = if (parentDir == "/") "/$prefix$randPart$suffix" else candidate

            if (dryRun) {
                // -u just prints — no atomicity, no creation.
                ctx.stdout.writeLine(candidatePath)
                return CommandResult(exitCode = 0)
            }

            if (ctx.process.fs.exists(candidatePath)) return@repeat // collision; retry

            try {
                if (directory) {
                    ctx.process.fs.mkdirs(candidatePath, mode = 0b111_000_000) // 0o700
                } else {
                    // 0o600 — owner-only, like GNU mktemp.
                    ctx.process.fs.writeBytes(candidatePath, ByteArray(0), mode = 0b110_000_000)
                }
            } catch (e: Exception) {
                if (!quiet) {
                    ctx.stderr.writeUtf8(
                        "mktemp: failed to create '$candidatePath': ${e.message ?: "I/O error"}\n",
                    )
                }
                return CommandResult(exitCode = 1)
            }
            ctx.stdout.writeLine(candidatePath)
            return CommandResult(exitCode = 0)
        }

        if (!quiet) {
            ctx.stderr.writeUtf8(
                "mktemp: failed to create unique file in $MAX_RETRIES tries\n",
            )
        }
        return CommandResult(exitCode = 1)
    }

    private fun randomString(n: Int): String =
        buildString(n) {
            repeat(n) {
                append(ALPHABET[random.nextInt(ALPHABET.length)])
            }
        }

    private companion object {
        const val DEFAULT_TEMPLATE = "tmp.XXXXXXXXXX"
        const val MIN_X_COUNT = 3
        const val MAX_RETRIES = 200
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        val HELP_TEXT =
            "Usage: mktemp [OPTION]... [TEMPLATE]\n" +
                "Create a temporary file or directory, safely, and print its name.\n\n" +
                "  -d, --directory     create a directory, not a file\n" +
                "  -q, --quiet         suppress diagnostics on failure\n" +
                "  -u, --dry-run       do not create anything; print a unique name\n" +
                "  -p DIR, --tmpdir[=DIR]  interpret TEMPLATE relative to DIR " +
                "(default \$TMPDIR or /tmp)\n" +
                "  -t                  interpret TEMPLATE as a single file name component (legacy)\n" +
                "      --suffix=SUFF   append SUFF to the template; template must end in X\n" +
                "      --help          display this help and exit\n\n" +
                "If TEMPLATE is omitted, uses tmp.XXXXXXXXXX. Trailing X's are replaced with\n" +
                "random alphanumeric characters; at least 3 X's are required.\n"
    }
}
