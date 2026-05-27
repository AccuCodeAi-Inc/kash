package com.accucodeai.kash.tools.openssl

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `openssl` — front-end dispatcher.
 *
 * v1 implements: version, help, dgst, md5/sha1/sha224/sha256/sha384/sha512
 * shortcuts, base64, passwd.
 *
 * Deferred to v2 (all return the same "not yet supported" error path):
 *   enc, rand, dgst -hmac, sha3 family, genrsa, genpkey, rsa, ec, rsautl,
 *   pkeyutl, x509, req, ca, ts, cms, s_client, s_server, smime, dhparam,
 *   ecparam, ciphers.
 */
public class OpenSslCommand :
    Command,
    CommandSpec {
    override val name: String = "openssl"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        if (args.isEmpty()) {
            ctx.stderr.writeUtf8("openssl: missing subcommand; try 'openssl help'\n")
            return CommandResult(exitCode = 1)
        }
        val sub = args[0]
        val rest = args.drop(1)
        return when (sub) {
            "version" -> {
                versionCmd(ctx)
            }

            "help", "--help", "-h" -> {
                helpCmd(ctx)
            }

            "dgst" -> {
                DgstSubcommand.run(rest, ctx, fixedAlg = null)
            }

            "md5" -> {
                DgstSubcommand.run(rest, ctx, fixedAlg = "md5")
            }

            "sha1" -> {
                DgstSubcommand.run(rest, ctx, fixedAlg = "sha1")
            }

            "sha224" -> {
                DgstSubcommand.run(rest, ctx, fixedAlg = "sha224")
            }

            "sha256" -> {
                DgstSubcommand.run(rest, ctx, fixedAlg = "sha256")
            }

            "sha384" -> {
                DgstSubcommand.run(rest, ctx, fixedAlg = "sha384")
            }

            "sha512" -> {
                DgstSubcommand.run(rest, ctx, fixedAlg = "sha512")
            }

            "base64" -> {
                Base64Subcommand.run(rest, ctx)
            }

            "passwd" -> {
                PasswdSubcommand.run(rest, ctx)
            }

            "enc" -> {
                EncSubcommand.run(rest, ctx)
            }

            "rand" -> {
                RandSubcommand.run(rest, ctx)
            }

            in DEFERRED -> {
                ctx.stderr.writeUtf8("openssl: '$sub' is not yet supported in this build\n")
                CommandResult(exitCode = 1)
            }

            else -> {
                ctx.stderr.writeUtf8("openssl: '$sub': unknown command\n")
                ctx.stderr.writeUtf8("openssl: try 'openssl help' for the supported subcommand list\n")
                CommandResult(exitCode = 1)
            }
        }
    }

    private suspend fun versionCmd(ctx: CommandContext): CommandResult {
        ctx.stdout.writeLine("kash openssl $VERSION (built-in)")
        return CommandResult(exitCode = 0)
    }

    private suspend fun helpCmd(ctx: CommandContext): CommandResult {
        ctx.stdout.writeLine("kash openssl $VERSION — supported subcommands:")
        ctx.stdout.writeLine("  version                          print version")
        ctx.stdout.writeLine("  help                             this help text")
        ctx.stdout.writeLine("  dgst -<alg> [-hex|-binary|-c|-r] [-out F] [-hmac KEY] [file..]")
        ctx.stdout.writeLine("       algs: md5, sha1, sha224, sha256 (default), sha384, sha512")
        ctx.stdout.writeLine("       also: -hmac-env VAR, -hmac-stdin, -list, -help")
        ctx.stdout.writeLine("  md5|sha1|sha224|sha256|sha384|sha512 [file..]  shortcut for 'dgst -<alg>'")
        ctx.stdout.writeLine("  base64 [-e|-d|-D] [-A] [-in F] [-out F] [-help]")
        ctx.stdout.writeLine("  passwd [-1|-apr1|-5|-6] -salt SALT [-stdin | -in F | password]")
        ctx.stdout.writeLine("       [-table] [-reverse] [-help]")
        ctx.stdout.writeLine("  enc -<cipher> [-e|-d] [-a] [-A] [-nopad] [-in F] [-out F]")
        ctx.stdout.writeLine("       Password mode (requires -pbkdf2): -k|-pass|-kfile, -iter N,")
        ctx.stdout.writeLine("         -md sha256|sha1|sha512, -S HEX, -nosalt, -p, -P")
        ctx.stdout.writeLine("       Raw key mode: -K HEX -iv HEX (no PBKDF2, no salt header)")
        ctx.stdout.writeLine("       Also: -list / -ciphers, -help")
        ctx.stdout.writeLine("       Ciphers: aes-128/192/256-cbc, aes-128/256-ctr, aes-128/256-gcm")
        ctx.stdout.writeLine("  rand [-hex|-base64] [-A] [-out F] NUM[K|M|G|T] [-help]")
        ctx.stdout.writeLine("")
        ctx.stdout.writeLine("Per-subcommand help: 'openssl <sub> -help'")
        ctx.stdout.writeLine("")
        ctx.stdout.writeLine("Not yet supported in this build:")
        ctx.stdout.writeLine("  genrsa genpkey rsa ec rsautl pkeyutl x509 req ca ts cms")
        ctx.stdout.writeLine("  s_client s_server smime dhparam ecparam ciphers list")
        ctx.stdout.writeLine("  sha3-* and shake* algorithms")
        return CommandResult(exitCode = 0)
    }

    public companion object {
        public const val VERSION: String = "0.1"
        private val DEFERRED: Set<String> =
            setOf(
                "genrsa",
                "genpkey",
                "rsa",
                "ec",
                "rsautl",
                "pkeyutl",
                "x509",
                "req",
                "ca",
                "ts",
                "cms",
                "s_client",
                "s_server",
                "smime",
                "dhparam",
                "ecparam",
                "ciphers",
                "sha3-224",
                "sha3-256",
                "sha3-384",
                "sha3-512",
                "shake128",
                "shake256",
            )
    }
}
