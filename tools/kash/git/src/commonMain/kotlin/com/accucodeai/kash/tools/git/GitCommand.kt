package com.accucodeai.kash.tools.git

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.git.GitHostAdapter
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.porcelain.GitSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitAddSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitBlameSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitBranchSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitCatFileSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitCheckoutSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitCherryPickSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitCleanSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitCloneSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitCommitSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitConfigSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitDescribeSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitDiffSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitFetchSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitHashObjectSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitInitSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitLogSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitLsFilesSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitLsTreeSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitMergeSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitMvSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitPullSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitPushSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitRebaseSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitReflogSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitRemoteSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitResetSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitRestoreSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitRevListSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitRevParseSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitRevertSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitRmSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitShowSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitStashSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitStatusSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitSwitchSubcommand
import com.accucodeai.kash.tools.git.porcelain.gitTagSubcommand
import com.accucodeai.kash.tools.git.seed.pushApplierFor
import com.accucodeai.kash.tools.git.seed.refResolverFor
import com.accucodeai.kash.tools.git.seed.resolverFor

/**
 * The `git` builtin. Dispatches `git <sub> <args...>` to a registered
 * [GitSubcommand] handler. The optional [adapter] is captured at
 * construction; subcommands consult it through the
 * [com.accucodeai.kash.tools.git.GitEnv] passed to each handler.
 *
 * Common flags ahead of the subcommand (`git -C <dir>`, `--git-dir`,
 * etc.) are parsed here, not by individual subcommands, matching real
 * git's argument grammar.
 */
public class GitCommand(
    private val adapter: GitHostAdapter? = null,
) : Command,
    CommandSpec {
    override val name: String = "git"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    private val subs: Map<String, GitSubcommand> =
        listOf(
            gitInitSubcommand(),
            gitAddSubcommand(),
            gitStatusSubcommand(),
            gitCommitSubcommand(),
            gitLogSubcommand(),
            gitShowSubcommand(),
            gitDiffSubcommand(),
            gitRevParseSubcommand(),
            gitLsFilesSubcommand(),
            gitBranchSubcommand(),
            gitSwitchSubcommand(),
            gitCheckoutSubcommand(),
            gitRestoreSubcommand(),
            gitResetSubcommand(),
            gitTagSubcommand(),
            gitPushSubcommand(),
            gitMergeSubcommand(),
            gitRebaseSubcommand(),
            gitCherryPickSubcommand(),
            gitRevertSubcommand(),
            gitRmSubcommand(),
            gitMvSubcommand(),
            gitCleanSubcommand(),
            gitStashSubcommand(),
            gitFetchSubcommand(),
            gitBlameSubcommand(),
            gitConfigSubcommand(),
            gitRemoteSubcommand(),
            gitRevListSubcommand(),
            gitCatFileSubcommand(),
            gitHashObjectSubcommand(),
            gitLsTreeSubcommand(),
            gitCloneSubcommand(),
            gitPullSubcommand(),
            gitReflogSubcommand(),
            gitDescribeSubcommand(),
        ).associateBy { it.name }

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        // Strip leading `-C <dir>` / `--git-dir=<...>` / `-c key=val` etc.
        var i = 0
        var chdir: String? = null
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-C" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("error: option \"-C\" requires a value\n")
                        return CommandResult(exitCode = 129)
                    }
                    chdir = args[i + 1]
                    i += 2
                }

                a.startsWith("-C") -> {
                    chdir = a.substring(2)
                    i++
                }

                a == "--version" -> {
                    ctx.stdout.writeUtf8("git version 2.50.0 (kash)\n")
                    return CommandResult(exitCode = 0)
                }

                // `git -c key=val <cmd>` / `git -ckey=val <cmd>` —
                // real git's one-shot config override. We consume the
                // flag + its value so the next argv element is the
                // subcommand, not a stray "user.email=foo" that would
                // bubble up as "not a git command". Honoring the
                // override against `.git/config` is a follow-up; for
                // now we warn-and-ignore so scripts that do
                // `git -c user.email=x commit` stop hard-failing.
                a == "-c" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("error: option \"-c\" requires a value\n")
                        return CommandResult(exitCode = 129)
                    }
                    ctx.stderr.writeUtf8("git: ignoring unsupported global override '-c ${args[i + 1]}'\n")
                    i += 2
                }

                a.startsWith("-c") && a.length > 2 && a.contains('=') -> {
                    ctx.stderr.writeUtf8("git: ignoring unsupported global override '$a'\n")
                    i++
                }

                a == "--help" || a == "-h" -> {
                    // `git --help [<sub>]` — overall help, or per-sub help
                    // if a name follows.
                    val target = args.getOrNull(i + 1)?.takeIf { !it.startsWith("-") }
                    return emitHelp(ctx, target)
                }

                a.startsWith("-") && a != "--" -> {
                    // We accept a couple of well-known leading flags and
                    // ignore the rest with a warning so scripts that
                    // sprinkle `-c key=val` don't die in v1.
                    ctx.stderr.writeUtf8("git: ignoring unsupported global option '$a'\n")
                    i++
                }

                a == "--" -> {
                    i++
                    break
                }

                else -> {
                    break
                }
            }
        }

        if (i >= args.size) {
            ctx.stderr.writeUtf8("usage: git <command> [<args>]\n")
            return CommandResult(exitCode = 1)
        }

        val subName = args[i]
        val subArgs = args.drop(i + 1)

        // `git help [<sub>]` is its own dispatch (not in the `subs` map).
        if (subName == "help") {
            val target = subArgs.firstOrNull { !it.startsWith("-") }
            return emitHelp(ctx, target)
        }

        // `git <sub> --help` / `git <sub> -h` — intercept BEFORE the sub
        // runs so a subcommand that doesn't understand `--help` itself
        // (which is all of them currently) still gives useful output.
        if (subArgs.firstOrNull() == "--help" || subArgs.firstOrNull() == "-h") {
            return emitHelp(ctx, subName)
        }

        val handler = subs[subName]
        if (handler == null) {
            ctx.stderr.writeUtf8("git: '$subName' is not a git command. See 'git --help'.\n")
            return CommandResult(exitCode = 1)
        }

        val effectiveCwd =
            when (val c = chdir) {
                null -> {
                    ctx.cwd
                }

                else -> {
                    if (c.startsWith("/")) {
                        c
                    } else if (ctx.cwd == "/") {
                        "/$c"
                    } else {
                        "${ctx.cwd}/$c"
                    }
                }
            }
        val env =
            GitEnv(
                adapter = adapter,
                cwd = effectiveCwd,
                resolver = resolverFor(adapter),
                pushApplier = pushApplierFor(adapter),
                refResolver = refResolverFor(adapter),
            )
        return handler.run(subArgs, ctx, env)
    }

    /**
     * Emit either the overall help (list of subcommands grouped by
     * function) or a specific subcommand's help. Matches real git's
     * formatting closely enough that an LLM trained on real git output
     * parses ours the same way.
     */
    private suspend fun emitHelp(
        ctx: CommandContext,
        sub: String?,
    ): CommandResult {
        if (sub == null) {
            ctx.stdout.writeUtf8(buildOverviewHelp())
            return CommandResult(exitCode = 0)
        }
        val info = HELP_INFO[sub]
        if (info == null) {
            ctx.stderr.writeUtf8("git: no help topic for '$sub'\n")
            return CommandResult(exitCode = 1)
        }
        ctx.stdout.writeUtf8(info.fullHelp(sub))
        return CommandResult(exitCode = 0)
    }

    private fun buildOverviewHelp(): String {
        val sb = StringBuilder()
        sb.append("usage: git [-C <path>] [--version] [--help] <command> [<args>]\n\n")
        sb.append("These are common Git commands used in various situations:\n\n")
        for (group in HELP_GROUPS) {
            sb.append(group.title).append('\n')
            // Width of the longest sub name in the group + 3 spaces.
            val pad = group.subs.maxOf { it.length } + 3
            for (name in group.subs) {
                val info = HELP_INFO[name] ?: continue
                sb
                    .append("   ")
                    .append(name.padEnd(pad))
                    .append(info.summary)
                    .append('\n')
            }
            sb.append('\n')
        }
        sb.append("'git help <command>' shows detailed usage for that command.\n")
        return sb.toString()
    }

    private companion object {
        private class SubHelp(
            val summary: String,
            val usage: String,
            val details: String = "",
        ) {
            fun fullHelp(name: String): String =
                buildString {
                    append("NAME\n    git-$name - $summary\n\n")
                    append("SYNOPSIS\n    $usage\n\n")
                    if (details.isNotBlank()) {
                        append("DESCRIPTION\n")
                        for (line in details.trimEnd().lines()) {
                            append("    ").append(line).append('\n')
                        }
                        append('\n')
                    }
                }
        }

        private class HelpGroup(
            val title: String,
            val subs: List<String>,
        )

        private val HELP_INFO: Map<String, SubHelp> =
            mapOf(
                "init" to
                    SubHelp(
                        summary = "Set up a new Git repository, or re-initialize an existing one",
                        usage = "git init [-q] [-b <branch>] [<directory>]",
                        details =
                            "Materializes a `.git/` layout (HEAD, config, refs, objects, hooks) " +
                                "at the current working directory or [<directory>]. The initial branch " +
                                "name defaults to `main`; override with -b.",
                    ),
                "add" to
                    SubHelp(
                        summary = "Stage file contents into the index",
                        usage = "git add [-A] [-u] [--] <pathspec>...",
                        details =
                            "Walks the given paths (or the whole work tree with -A) and stages each " +
                                "file's current content. Skips nested-repo directories. With -u, only " +
                                "modifications and deletions of already-tracked paths are staged.",
                    ),
                "status" to
                    SubHelp(
                        summary = "Report the state of the working tree",
                        usage = "git status [--porcelain[=v1|=v2]] [-s] [--branch|-b]",
                        details =
                            "Lists files modified vs. HEAD, vs. index, and untracked. --porcelain " +
                                "(or -s) emits the stable two-column form; --porcelain=v2 adds " +
                                "# branch.oid/head/upstream/ab headers. --branch / -b adds a " +
                                "`## <branch>...<upstream> [ahead N, behind M]` header.",
                    ),
                "commit" to
                    SubHelp(
                        summary = "Commit the staged changes",
                        usage =
                            "git commit [-m <msg>] [--amend] [--fixup=<rev>|--squash=<rev>] " +
                                "[--allow-empty] [--no-verify] [-q]",
                        details =
                            "Writes a commit object capturing the index, advances HEAD, and fires " +
                                "the host pre-commit validator + repo hooks/pre-commit. --no-verify " +
                                "skips the repo hook only; the host validator is unbypassable. " +
                                "--fixup / --squash produce the magic-prefixed subjects that " +
                                "`git rebase --autosquash` picks up.",
                    ),
                "log" to
                    SubHelp(
                        summary = "Display the commit history",
                        usage =
                            "git log [--oneline] [--graph] [--pretty=<fmt>] [-p] [--stat] " +
                                "[-n <N>] [<revspec>] [-- <path>...]",
                        details =
                            "First-parent walk (or topological with --graph). Pretty: %H %h %T %t %P " +
                                "%p %an %ae %ad %ai %at %cn %ce %s %b %B %n %% plus presets " +
                                "(oneline, short, medium, full, fuller). --graph renders ASCII lanes.",
                    ),
                "show" to
                    SubHelp(
                        summary = "Print the contents of Git objects",
                        usage = "git show <rev> | git show <rev>:<path>",
                        details =
                            "`<rev>:<path>` returns the blob bytes at that path. Bare `<rev>` " +
                                "prints the commit header (or blob/tree content for those object types).",
                    ),
                "diff" to
                    SubHelp(
                        summary = "Compare commits, the index, and the working tree",
                        usage = "git diff [--cached] [--name-only] [<rev>] [<rev2>|<rev>..<rev2>]",
                        details =
                            "Default: work tree vs index. --cached: index vs HEAD. <rev>: " +
                                "work tree vs <rev>. <rev>..<rev2>: rev-vs-rev. Unified diff format.",
                    ),
                "rev-parse" to
                    SubHelp(
                        summary = "Parse and normalize revision and option arguments",
                        usage = "git rev-parse [--short[=N]] [--abbrev-ref] [--verify] <args>...",
                        details =
                            "Resolves revspecs to SHAs. Also surfaces --show-toplevel, --git-dir, " +
                                "--is-inside-work-tree.",
                    ),
                "ls-files" to
                    SubHelp(
                        summary = "List tracked files and their index metadata",
                        usage = "git ls-files [-s|--stage] [-z]",
                        details =
                            "Lists tracked paths. -s adds `<mode> <sha> <stage>\\t` prefix.",
                    ),
                "branch" to
                    SubHelp(
                        summary = "Create, list, or remove branches",
                        usage = "git branch [-l|-a|--show-current|-d|-D] [<name> [<start>]]",
                    ),
                "switch" to
                    SubHelp(
                        summary = "Move HEAD to another branch",
                        usage = "git switch [-c|-C] [-f] <branch>|<sha>",
                        details =
                            "Updates HEAD and the work tree. -c/-C creates the branch. -f " +
                                "discards local changes that would be overwritten.",
                    ),
                "checkout" to
                    SubHelp(
                        summary = "Change branches or restore working-tree files",
                        usage = "git checkout [-b|-B] [-f] <branch>|<sha>",
                        details = "Same as `git switch` in this implementation.",
                    ),
                "restore" to
                    SubHelp(
                        summary = "Restore files in the working tree",
                        usage = "git restore [--staged] [--source=<rev>] <path>...",
                        details =
                            "Without --staged: copy from the index (or --source rev) into the work " +
                                "tree. With --staged: copy from HEAD (or --source rev) into the index.",
                    ),
                "reset" to
                    SubHelp(
                        summary = "Move HEAD (and optionally index/tree) to a given state",
                        usage = "git reset [--soft|--mixed|--hard] [<rev>]",
                        details =
                            "--soft: move HEAD only. --mixed (default): also refresh the index. " +
                                "--hard: also overwrite the work tree.",
                    ),
                "tag" to
                    SubHelp(
                        summary = "Create, list, or remove tags",
                        usage = "git tag [-l] | git tag <name> [<rev>] | git tag -d <name>",
                        details = "Lightweight tags only; -a (annotated) falls back with a warning.",
                    ),
                "push" to
                    SubHelp(
                        summary = "Upload local refs and their objects to a remote",
                        usage = "git push [-u] [<remote> [<branch>]]",
                        details =
                            "Hands new commits (oldest-first) + their reachable trees + new blobs " +
                                "to the host's GitPushApplier. Without an applier: exit 128 'no " +
                                "remote configured'.",
                    ),
                "merge" to
                    SubHelp(
                        summary = "Merge other histories into the current branch",
                        usage = "git merge [--no-commit] [-m <msg>] <branch> | git merge --abort",
                        details =
                            "Fast-forwards when possible. Otherwise computes a merge base and runs " +
                                "a per-file 3-way merge. Conflicts pause with MERGE_HEAD/MERGE_MSG " +
                                "and stage 1/2/3 entries; resolve and `git commit` to finalize.",
                    ),
                "rebase" to
                    SubHelp(
                        summary = "Replay commits onto a new base",
                        usage =
                            "git rebase [--autosquash] <upstream> | " +
                                "git rebase --continue|--abort|--skip",
                        details =
                            "Linear rebase via cherry-pick-style replay. Conflicts pause with " +
                                "rebase-merge/ state; `--continue` after resolution picks up where " +
                                "we stopped. --autosquash reorders fixup!/squash! commits next to " +
                                "their targets and folds them in.",
                    ),
                "cherry-pick" to
                    SubHelp(
                        summary = "Apply an existing commit's diff onto HEAD",
                        usage = "git cherry-pick [-n] <commit>",
                        details =
                            "3-way merge of (commit^, HEAD, commit) onto HEAD, then commits with " +
                                "the picked commit's author + message. -n skips the commit step.",
                    ),
                "revert" to
                    SubHelp(
                        summary = "Create commits that undo earlier ones",
                        usage = "git revert [-n] <commit>",
                        details = "Inverse of cherry-pick — applies the negation of <commit> onto HEAD.",
                    ),
                "rm" to
                    SubHelp(
                        summary = "Delete tracked files from the tree and index",
                        usage = "git rm [-r] [--cached] [-f] <path>...",
                    ),
                "mv" to
                    SubHelp(
                        summary = "Rename or relocate a tracked file",
                        usage = "git mv <from> <to>",
                    ),
                "clean" to
                    SubHelp(
                        summary = "Delete untracked files from the working tree",
                        usage = "git clean [-n] [-f] [-d] [-x]",
                        details =
                            "Refuses without -f or -n (matches real git's clean.requireForce). " +
                                "-d also removes untracked directories.",
                    ),
                "stash" to
                    SubHelp(
                        summary = "Set uncommitted changes aside for later",
                        usage = "git stash [push [-m <msg>] | pop | apply | drop | list | show]",
                    ),
                "fetch" to
                    SubHelp(
                        summary = "Retrieve objects and refs from a remote",
                        usage = "git fetch [<remote>] [<branch>]",
                        details =
                            "Asks the host's GitRefResolver for the current upstream tips and " +
                                "advances refs/remotes/origin/<branch>. Object content is fetched " +
                                "lazily on first access.",
                    ),
                "blame" to
                    SubHelp(
                        summary = "Annotate each line with its last-changing commit",
                        usage = "git blame [<rev>] <path>",
                    ),
                "config" to
                    SubHelp(
                        summary = "Read or write repository and global configuration",
                        usage = "git config [--global] [--unset|--get|--list] <key> [<value>]",
                        details =
                            "Backed by .git/config (or ~/.gitconfig with --global). Dotted keys: " +
                                "user.email, branch.<n>.remote, etc. Returns exit 1 when --get " +
                                "finds no value.",
                    ),
                "remote" to
                    SubHelp(
                        summary = "Add, remove, or inspect remotes",
                        usage = "git remote [-v] | git remote add|remove|set-url|get-url|show <name> [<url>]",
                        details =
                            "Backed by [remote \"<name>\"] sections in .git/config. -v prints " +
                                "fetch + push URLs.",
                    ),
                "rev-list" to
                    SubHelp(
                        summary = "Walk and list commits newest-first",
                        usage = "git rev-list [--max-count=N] [--reverse] [--count] [^<rev>|<a>..<b>] <revs>...",
                        details =
                            "Walks ancestry from each <rev>; excludes ancestry reachable from " +
                                "any ^<rev>. --count emits only the integer count.",
                    ),
                "cat-file" to
                    SubHelp(
                        summary = "Emit an object's content, type, or size",
                        usage = "git cat-file (-t|-s|-e|-p) <object>",
                        details =
                            "-t prints the type, -s the size, -e exits 0 if the object exists, " +
                                "-p pretty-prints (raw bytes for blob/commit/tag; tree entries for tree).",
                    ),
                "hash-object" to
                    SubHelp(
                        summary = "Hash a file and optionally store it as a blob",
                        usage = "git hash-object [-w] [-t <type>] (--stdin | <file>...)",
                        details =
                            "Hashes file content (or stdin) as a blob (default) or the type given " +
                                "to -t. -w also writes the framed object into the loose store.",
                    ),
                "ls-tree" to
                    SubHelp(
                        summary = "Enumerate the entries of a tree object",
                        usage = "git ls-tree [-r] [--name-only] [-z] <tree-ish> [<path>...]",
                        details =
                            "Resolves <tree-ish> to a tree (commits are peeled). -r recurses into " +
                                "subtrees.",
                    ),
                "clone" to
                    SubHelp(
                        summary = "Copy a repository into a new directory",
                        usage = "git clone <url> [<dir>]",
                        details =
                            "If <dir> already has a .git/, it's treated as the seed (the typical " +
                                "kash flow). Otherwise we init + fetch via the host's GitRefResolver.",
                    ),
                "pull" to
                    SubHelp(
                        summary = "Fetch and integrate updates from a remote or branch",
                        usage = "git pull [--rebase] [<remote> [<branch>]]",
                        details =
                            "Sugar for fetch + merge (or fetch + rebase with --rebase). Defaults " +
                                "to origin and the current branch.",
                    ),
                "reflog" to
                    SubHelp(
                        summary = "Inspect or expire reference logs",
                        usage = "git reflog [show] [<ref>] | git reflog exists <ref>",
                        details =
                            "Lists log entries for <ref> (default HEAD). HEAD@{N} in revspecs " +
                                "resolves to the value <ref> had N moves ago. Only commit, " +
                                "checkout/switch, and branch creation currently emit entries.",
                    ),
                "describe" to
                    SubHelp(
                        summary = "Derive a readable name for a commit from nearby refs",
                        usage = "git describe [--tags] [--always] [--abbrev=<N>] [<rev>]",
                        details =
                            "Walks back from <rev> (default HEAD) for the closest tag. Emits the " +
                                "tag name on a direct hit, or <tag>-<N>-g<short-sha> otherwise.",
                    ),
            )

        private val HELP_GROUPS: List<HelpGroup> =
            listOf(
                HelpGroup(
                    "start a working area",
                    listOf("init"),
                ),
                HelpGroup(
                    "work on the current change",
                    listOf("add", "mv", "restore", "rm"),
                ),
                HelpGroup(
                    "examine the history and state",
                    listOf("blame", "diff", "log", "ls-files", "show", "status"),
                ),
                HelpGroup(
                    "grow, mark and tweak your common history",
                    listOf("branch", "checkout", "commit", "merge", "rebase", "reset", "switch", "tag"),
                ),
                HelpGroup(
                    "collaborate (host-adapter mediated)",
                    listOf("clone", "fetch", "pull", "push", "remote"),
                ),
                HelpGroup(
                    "low-level plumbing",
                    listOf("cat-file", "hash-object", "ls-tree"),
                ),
                HelpGroup(
                    "other",
                    listOf(
                        "cherry-pick",
                        "clean",
                        "config",
                        "describe",
                        "reflog",
                        "revert",
                        "rev-list",
                        "rev-parse",
                        "stash",
                    ),
                ),
            )
    }
}

/**
 * Per-invocation context handed to a [GitSubcommand]. Bundles the
 * (effective) cwd — after any `-C` flag has been applied — and the
 * optional host adapter. Subcommands consult [adapter] to decide
 * whether richer integration is available (pre-commit validation,
 * remote ops, sync signal) or whether they're in plain-local mode.
 */
public class GitEnv(
    public val adapter: GitHostAdapter?,
    public val cwd: String,
    /**
     * Object-resolver pulled from an [GitRepoSeed.OnDemand] seed, if
     * the adapter has one. Subcommands thread this into
     * [GitRepo.openFromCwd] so an [ObjectStore] miss past the
     * materialized horizon walks back to the host. Null for plain
     * Synthetic/RealGit/Empty seeds and adapter-less mode.
     */
    public val resolver: com.accucodeai.kash.api.git.GitObjectResolver? = null,
    /**
     * Optional push applier from an [GitRepoSeed.OnDemand] seed. When
     * non-null, `git push` walks the new commits and hands them to the
     * applier; when null, the command surfaces "no remote configured."
     */
    public val pushApplier: com.accucodeai.kash.api.git.GitPushApplier? = null,
    /**
     * Ref resolver for `git fetch`-style ops. Lets the LLM ask "what
     * does upstream think `refs/heads/main` is right now?" and bring
     * the local tracking ref in line. Null for non-OnDemand seeds.
     */
    public val refResolver: com.accucodeai.kash.api.git.GitRefResolver? = null,
)
