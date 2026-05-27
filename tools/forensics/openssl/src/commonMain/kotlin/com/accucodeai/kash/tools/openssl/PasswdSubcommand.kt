package com.accucodeai.kash.tools.openssl

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound

/**
 * `openssl passwd` — hash a password with a crypt(3)-style scheme.
 *
 *   -1            md5-crypt (`$1$`)
 *   -apr1         Apache md5-crypt (`$apr1$`)
 *   -5            SHA-256-crypt (`$5$`)
 *   -6            SHA-512-crypt (`$6$`, default)
 *   -salt SALT    use SALT instead of generating one (REQUIRED here — kash
 *                 has no CSPRNG wired into this build yet)
 *   -stdin        read password from stdin (first line, newline stripped)
 *   [password]    positional password
 *
 * Note: when neither -salt nor a generator is available we error out
 * rather than emit a deterministic-but-unsafe salt.
 */
internal object PasswdSubcommand {
    private val HELP =
        """
        Usage: openssl passwd [-1|-apr1|-5|-6] [-salt SALT] [-in FILE | -stdin |
                              PASSWORD] [-table] [-reverse]

          -1            md5-crypt (`${'$'}1${'$'}`)
          -apr1         Apache md5-crypt (`${'$'}apr1${'$'}`)
          -5            SHA-256-crypt (`${'$'}5${'$'}`)
          -6            SHA-512-crypt (`${'$'}6${'$'}`)
          -salt SALT    use SALT (required in this build — no RNG-salt yet)
          -stdin        read password(s) from stdin (one per line)
          -in FILE      read password(s) from FILE (one per line)
          -table        emit '<password>\\t<hash>' per entry
          -reverse      with -table, emit '<hash>\\t<password>' instead
          -help, -h     this help

        Not supported in this build: -aixmd5, -noverify, -quiet (accepted but
        ignored), -rand, -writerand.
        """.trimIndent() + "\n"

    suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var scheme = "6"
        var apr1 = false
        var salt: String? = null
        var fromStdin = false
        var inFile: String? = null
        var table = false
        var reverse = false
        var password: String? = null

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when (a) {
                "-help", "-h" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                "-1" -> {
                    scheme = "1"
                    apr1 = false
                }

                "-apr1" -> {
                    scheme = "1"
                    apr1 = true
                }

                "-5" -> {
                    scheme = "5"
                    apr1 = false
                }

                "-6" -> {
                    scheme = "6"
                    apr1 = false
                }

                "-salt" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("openssl passwd: -salt requires an argument\n")
                        return CommandResult(exitCode = 1)
                    }
                    salt = args[i + 1]
                    i += 2
                    continue
                }

                "-stdin" -> {
                    fromStdin = true
                }

                "-in" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("openssl passwd: -in requires an argument\n")
                        return CommandResult(exitCode = 1)
                    }
                    inFile = args[i + 1]
                    i += 2
                    continue
                }

                "-table" -> {
                    table = true
                }

                "-reverse" -> {
                    reverse = true
                }

                "-noverify", "-quiet" -> {
                    Unit
                }

                "-aixmd5" -> {
                    ctx.stderr.writeUtf8("openssl passwd: -aixmd5 is not supported in this build\n")
                    return CommandResult(exitCode = 1)
                }

                else -> {
                    if (a.startsWith("-") && a.length > 1) {
                        ctx.stderr.writeUtf8("openssl passwd: unknown option: $a\n")
                        return CommandResult(exitCode = 1)
                    }
                    if (password != null) {
                        ctx.stderr.writeUtf8("openssl passwd: multiple positional passwords not supported\n")
                        return CommandResult(exitCode = 1)
                    }
                    password = a
                }
            }
            i++
        }

        if (salt == null) {
            ctx.stderr.writeUtf8("openssl passwd: -salt is required in this build (no RNG)\n")
            return CommandResult(exitCode = 1)
        }

        // Collect passwords: positional (single), -stdin (lines), or -in FILE (lines).
        val passwords: List<String> =
            when {
                inFile != null -> {
                    val text =
                        try {
                            val src = ctx.process.fs.source(resolvePath(ctx.process.cwd, inFile))
                            try {
                                src.readUtf8Text()
                            } finally {
                                src.close()
                            }
                        } catch (_: FileNotFound) {
                            ctx.stderr.writeUtf8("openssl passwd: $inFile: No such file or directory\n")
                            return CommandResult(exitCode = 1)
                        }
                    text.split('\n').filter { it.isNotEmpty() }
                }

                fromStdin -> {
                    ctx.stdin
                        .readUtf8Text()
                        .split('\n')
                        .filter { it.isNotEmpty() }
                }

                password != null -> {
                    listOf(password)
                }

                else -> {
                    ctx.stderr.writeUtf8(
                        "openssl passwd: no password supplied; pass one as an argument or use -stdin / -in FILE\n",
                    )
                    return CommandResult(exitCode = 1)
                }
            }

        if (passwords.isEmpty()) {
            ctx.stderr.writeUtf8("openssl passwd: no passwords to hash\n")
            return CommandResult(exitCode = 1)
        }

        for (pwd in passwords) {
            val pwdBytes = pwd.encodeToByteArray()
            val hash =
                when (scheme) {
                    "1" -> {
                        Crypt.md5Crypt(pwdBytes, salt, magic = if (apr1) "\$apr1\$" else "\$1\$")
                    }

                    "5" -> {
                        Crypt.shaCrypt(pwdBytes, salt, bits = 256)
                    }

                    "6" -> {
                        Crypt.shaCrypt(pwdBytes, salt, bits = 512)
                    }

                    else -> {
                        ctx.stderr.writeUtf8("openssl passwd: unsupported scheme\n")
                        return CommandResult(exitCode = 1)
                    }
                }
            val line =
                when {
                    table && reverse -> "$hash\t$pwd"
                    table -> "$pwd\t$hash"
                    else -> hash
                }
            ctx.stdout.writeLine(line)
        }
        return CommandResult(exitCode = 0)
    }
}
