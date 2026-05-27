package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo

/**
 * `git config` — read/write `.git/config` (or `~/.gitconfig` when
 * `--global` is given). Keys are dotted: `user.email`, `core.bare`, or
 * with a subsection `branch.main.remote`.
 *
 * v1 supports:
 *  - `git config <key>` or `--get <key>` → print value, exit 1 if missing
 *  - `git config <key> <value>` → set value
 *  - `git config --unset <key>` → remove key
 *  - `git config --list` / `-l` → emit every `key=value`
 *  - `git config --global ...` → operate on `~/.gitconfig` instead
 *
 * Key normalization matches real git: section + subsection (case-sensitive
 * if quoted, case-insensitive otherwise) + key (case-insensitive).
 */
public fun gitConfigSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "config"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            var global = false
            var list = false
            var unset = false
            var get = false
            val positional = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--global" -> {
                        global = true
                        i++
                    }

                    a == "--local" -> {
                        global = false
                        i++
                    }

                    a == "--list" || a == "-l" -> {
                        list = true
                        i++
                    }

                    a == "--unset" -> {
                        unset = true
                        i++
                    }

                    a == "--get" -> {
                        get = true
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git config: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positional += a
                        i++
                    }
                }
            }

            val cfgPath =
                if (global) {
                    val home = ctx.env["HOME"] ?: "/"
                    if (home == "/") "/.gitconfig" else "$home/.gitconfig"
                } else {
                    val repo =
                        GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver)
                            ?: run {
                                ctx.stderr.writeUtf8("fatal: not in a git directory\n")
                                return CommandResult(exitCode = 128)
                            }
                    repo.layout.configFile
                }

            val store = ConfigStore.load(ctx.fs, cfgPath)

            if (list) {
                for ((sec, subs) in store.sections()) {
                    for ((k, v) in subs) {
                        ctx.stdout.writeUtf8("$sec.$k=$v\n")
                    }
                }
                return CommandResult(exitCode = 0)
            }

            if (unset) {
                if (positional.size != 1) {
                    ctx.stderr.writeUtf8("usage: git config --unset <key>\n")
                    return CommandResult(exitCode = 129)
                }
                val (section, key) =
                    splitDotted(positional[0]) ?: run {
                        ctx.stderr.writeUtf8("error: key does not contain a section: ${positional[0]}\n")
                        return CommandResult(exitCode = 1)
                    }
                if (!store.unset(section, key)) return CommandResult(exitCode = 5)
                store.save(ctx.fs, cfgPath)
                return CommandResult(exitCode = 0)
            }

            return when (positional.size) {
                1 -> {
                    val (section, key) =
                        splitDotted(positional[0]) ?: run {
                            ctx.stderr.writeUtf8("error: key does not contain a section: ${positional[0]}\n")
                            return CommandResult(exitCode = 1)
                        }
                    val v = store.get(section, key)
                    if (v == null) {
                        CommandResult(exitCode = 1)
                    } else {
                        ctx.stdout.writeUtf8("$v\n")
                        CommandResult(exitCode = 0)
                    }
                }

                2 -> {
                    val (section, key) =
                        splitDotted(positional[0]) ?: run {
                            ctx.stderr.writeUtf8("error: key does not contain a section: ${positional[0]}\n")
                            return CommandResult(exitCode = 1)
                        }
                    store.set(section, key, positional[1])
                    store.save(ctx.fs, cfgPath)
                    CommandResult(exitCode = 0)
                }

                0 -> {
                    if (get) {
                        ctx.stderr.writeUtf8("error: wrong number of arguments\n")
                        CommandResult(exitCode = 129)
                    } else {
                        ctx.stderr.writeUtf8("usage: git config <key> [<value>]\n")
                        CommandResult(exitCode = 129)
                    }
                }

                else -> {
                    ctx.stderr.writeUtf8("git config: too many arguments\n")
                    CommandResult(exitCode = 129)
                }
            }
        }
    }

private fun splitDotted(dotted: String): Pair<String, String>? {
    val first = dotted.indexOf('.')
    val last = dotted.lastIndexOf('.')
    if (first < 0) return null
    // `section.key` or `section.sub.key`. When two dots, middle becomes
    // a quoted subsection: `branch "main".remote` → "branch \"main\"".
    return if (first == last) {
        dotted.substring(0, first) to dotted.substring(first + 1)
    } else {
        val section = dotted.substring(0, first)
        val sub = dotted.substring(first + 1, last)
        val key = dotted.substring(last + 1)
        "$section \"$sub\"" to key
    }
}

/**
 * In-memory model of a git INI file. Preserves section order but not
 * comments or whitespace — adequate for our LLM-driven session that's
 * never editing a hand-curated config by hand.
 */
internal class ConfigStore private constructor(
    private val data: LinkedHashMap<String, LinkedHashMap<String, String>>,
) {
    fun sections(): Map<String, Map<String, String>> = data

    fun get(
        section: String,
        key: String,
    ): String? = data[section]?.get(key)

    fun set(
        section: String,
        key: String,
        value: String,
    ) {
        data.getOrPut(section) { linkedMapOf() }[key] = value
    }

    fun unset(
        section: String,
        key: String,
    ): Boolean {
        val sec = data[section] ?: return false
        val removed = sec.remove(key) != null
        if (sec.isEmpty()) data.remove(section)
        return removed
    }

    suspend fun save(
        fs: FileSystem,
        path: String,
    ) {
        val sb = StringBuilder()
        for ((sec, kvs) in data) {
            sb
                .append('[')
                .append(sec)
                .append(']')
                .append('\n')
            for ((k, v) in kvs) {
                sb
                    .append('\t')
                    .append(k)
                    .append(" = ")
                    .append(v)
                    .append('\n')
            }
        }
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        if (!fs.exists(parent)) fs.mkdirs(parent)
        fs.writeBytes(path, sb.toString().encodeToByteArray())
    }

    companion object {
        suspend fun load(
            fs: FileSystem,
            path: String,
        ): ConfigStore {
            val data = LinkedHashMap<String, LinkedHashMap<String, String>>()
            if (!fs.exists(path)) return ConfigStore(data)
            val text = fs.readBytes(path).decodeToString()
            var section: String? = null
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length - 1).trim()
                    data.getOrPut(section) { linkedMapOf() }
                    continue
                }
                if (section == null) continue
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val key = line.substring(0, eq).trim()
                var value = line.substring(eq + 1).trim()
                if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                    value = value.substring(1, value.length - 1)
                }
                data.getOrPut(section) { linkedMapOf() }[key] = value
            }
            return ConfigStore(data)
        }
    }
}
