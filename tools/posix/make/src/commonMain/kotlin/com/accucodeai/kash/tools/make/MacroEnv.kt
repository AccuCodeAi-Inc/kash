package com.accucodeai.kash.tools.make

public enum class MacroOrigin { DEFAULT, ENVIRONMENT, FILE, COMMAND_LINE, OVERRIDE, AUTOMATIC }

public data class MacroValue(
    val raw: String,
    val flavor: MacroFlavor,
    val origin: MacroOrigin,
    val exported: Boolean = false,
)

public class MacroEnv {
    private val table = LinkedHashMap<String, MacroValue>()

    public fun snapshotNames(): List<String> = table.keys.toList()

    public fun get(name: String): MacroValue? = table[name]

    public fun rawOrEmpty(name: String): String = table[name]?.raw ?: ""

    public fun isDefined(name: String): Boolean = table.containsKey(name)

    public fun put(
        name: String,
        value: MacroValue,
    ) {
        val existing = table[name]
        if (existing != null && existing.origin.outranks(value.origin)) return
        table[name] = value.copy(exported = existing?.exported == true || value.exported)
    }

    public fun export(name: String) {
        val v = table[name]
        if (v != null) {
            table[name] = v.copy(exported = true)
        } else {
            table[name] =
                MacroValue(raw = "", flavor = MacroFlavor.RECURSIVE, origin = MacroOrigin.FILE, exported = true)
        }
    }

    public fun assign(
        name: String,
        flavor: MacroFlavor,
        rhs: String,
        origin: MacroOrigin,
    ) {
        val existing = table[name]
        if (existing != null && existing.origin.outranks(origin)) return
        val newValue =
            when (flavor) {
                MacroFlavor.RECURSIVE -> {
                    MacroValue(rhs, MacroFlavor.RECURSIVE, origin, existing?.exported == true)
                }

                MacroFlavor.IMMEDIATE -> {
                    MacroValue(expand(rhs), MacroFlavor.IMMEDIATE, origin, existing?.exported == true)
                }

                MacroFlavor.IMMEDIATE_TRIPLE -> {
                    MacroValue(
                        expand(rhs).replace("$", "$$"),
                        MacroFlavor.IMMEDIATE,
                        origin,
                        existing?.exported == true,
                    )
                }

                MacroFlavor.CONDITIONAL -> {
                    if (existing != null) return
                    MacroValue(rhs, MacroFlavor.RECURSIVE, origin, false)
                }

                MacroFlavor.APPEND -> {
                    if (existing == null) {
                        MacroValue(rhs, MacroFlavor.RECURSIVE, origin)
                    } else {
                        val sep = if (existing.raw.isEmpty()) "" else " "
                        when (existing.flavor) {
                            MacroFlavor.RECURSIVE -> {
                                existing.copy(raw = existing.raw + sep + rhs)
                            }

                            MacroFlavor.IMMEDIATE, MacroFlavor.IMMEDIATE_TRIPLE -> {
                                existing.copy(raw = existing.raw + sep + expand(rhs))
                            }

                            else -> {
                                existing.copy(raw = existing.raw + sep + rhs)
                            }
                        }
                    }
                }

                MacroFlavor.SHELL -> {
                    MacroValue("", MacroFlavor.IMMEDIATE, origin, existing?.exported == true)
                }
            }
        table[name] = newValue
    }

    public suspend fun assignSuspending(
        name: String,
        flavor: MacroFlavor,
        rhs: String,
        origin: MacroOrigin,
        shellRunner: suspend (String) -> String,
    ) {
        if (flavor != MacroFlavor.SHELL) {
            assign(name, flavor, rhs, origin)
            return
        }
        val existing = table[name]
        if (existing != null && existing.origin.outranks(origin)) return
        val out = shellRunner(expand(rhs))
        table[name] = MacroValue(out, MacroFlavor.IMMEDIATE, origin, existing?.exported == true)
    }

    public fun expand(input: String): String = Expander(this).expand(input)

    public fun exportedMap(): Map<String, String> =
        table.entries
            .filter { it.value.exported }
            .associate { it.key to expand(it.value.raw) }
}

private fun MacroOrigin.outranks(other: MacroOrigin): Boolean {
    val rank: (MacroOrigin) -> Int = {
        when (it) {
            MacroOrigin.DEFAULT -> 0
            MacroOrigin.ENVIRONMENT -> 1
            MacroOrigin.FILE -> 2
            MacroOrigin.COMMAND_LINE -> 3
            MacroOrigin.OVERRIDE -> 4
            MacroOrigin.AUTOMATIC -> 5
        }
    }
    return rank(this) > rank(other)
}

private class Expander(
    private val env: MacroEnv,
) {
    fun expand(input: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '$') {
                if (i + 1 >= input.length) {
                    sb.append('$')
                    i++
                    continue
                }
                when (val nx = input[i + 1]) {
                    '$' -> {
                        sb.append('$')
                        i += 2
                    }

                    '(', '{' -> {
                        val close = if (nx == '(') ')' else '}'
                        val end = findMatchingClose(input, i + 2, nx, close)
                        if (end < 0) {
                            sb.append('$')
                            i++
                        } else {
                            val body = input.substring(i + 2, end)
                            sb.append(evalRef(body))
                            i = end + 1
                        }
                    }

                    else -> {
                        sb.append(lookup(nx.toString()))
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun findMatchingClose(
        s: String,
        from: Int,
        open: Char,
        close: Char,
    ): Int {
        var depth = 0
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '$' && i + 1 < s.length && (s[i + 1] == '(' || s[i + 1] == '{')) {
                val innerOpen = s[i + 1]
                val innerClose = if (innerOpen == '(') ')' else '}'
                val inner = findMatchingClose(s, i + 2, innerOpen, innerClose)
                if (inner < 0) return -1
                i = inner + 1
                continue
            }
            if (c == open) {
                depth++
            } else if (c == close) {
                if (depth == 0) return i
                depth--
            }
            i++
        }
        return -1
    }

    private fun evalRef(body: String): String {
        val firstSpace = body.indexOf(' ')
        val firstTab = body.indexOf('\t')
        val firstWs =
            when {
                firstSpace < 0 -> firstTab
                firstTab < 0 -> firstSpace
                else -> minOf(firstSpace, firstTab)
            }
        val colonIdx = body.indexOf(':')
        if (firstWs > 0 && (colonIdx < 0 || firstWs < colonIdx)) {
            val fname = body.substring(0, firstWs)
            if (fname in BUILTIN_FUNCS) {
                val argText = body.substring(firstWs + 1)
                return callFunction(fname, argText)
            }
        }
        if (colonIdx > 0) {
            val name = expand(body.substring(0, colonIdx))
            val subst = body.substring(colonIdx + 1)
            val raw = env.get(name)?.raw ?: return ""
            val expanded =
                if (env.get(name)?.flavor == MacroFlavor.RECURSIVE) expand(raw) else raw
            return applySubst(expanded, subst)
        }
        val name = expand(body)
        return lookup(name)
    }

    private fun applySubst(
        value: String,
        spec: String,
    ): String {
        val eqIdx = spec.indexOf('=')
        if (eqIdx < 0) return value
        val pat = expand(spec.substring(0, eqIdx))
        val rep = expand(spec.substring(eqIdx + 1))
        val words = value.split(Regex("[ \t]+")).filter { it.isNotEmpty() }
        return if ('%' in pat || '%' in rep) {
            val pIdx = pat.indexOf('%')
            val rIdx = rep.indexOf('%')
            val pPrefix = if (pIdx >= 0) pat.substring(0, pIdx) else pat
            val pSuffix = if (pIdx >= 0) pat.substring(pIdx + 1) else ""
            val rPrefix = if (rIdx >= 0) rep.substring(0, rIdx) else rep
            val rSuffix = if (rIdx >= 0) rep.substring(rIdx + 1) else ""
            words.joinToString(" ") { w ->
                if (w.startsWith(pPrefix) && w.endsWith(pSuffix) &&
                    w.length >= pPrefix.length + pSuffix.length
                ) {
                    val stem = w.substring(pPrefix.length, w.length - pSuffix.length)
                    if (rIdx >= 0) rPrefix + stem + rSuffix else rPrefix
                } else {
                    w
                }
            }
        } else {
            words.joinToString(" ") { w -> if (w.endsWith(pat)) w.removeSuffix(pat) + rep else w }
        }
    }

    private fun lookup(name: String): String {
        val v = env.get(name) ?: return ""
        return if (v.flavor == MacroFlavor.RECURSIVE) expand(v.raw) else v.raw
    }

    private fun callFunction(
        name: String,
        argText: String,
    ): String {
        val args = splitArgs(argText, expected = ARG_COUNTS[name] ?: -1)
        val exp = args.map { expand(it) }
        return when (name) {
            "subst" -> {
                if (exp.size >= 3) exp[2].replace(exp[0], exp[1]) else ""
            }

            "patsubst" -> {
                if (exp.size >= 3) applySubst(exp[2], exp[0] + "=" + exp[1]) else ""
            }

            "strip" -> {
                if (exp.isNotEmpty()) {
                    exp[0]
                        .trim()
                        .split(
                            Regex("[ \t]+"),
                        ).filter { it.isNotEmpty() }
                        .joinToString(" ")
                } else {
                    ""
                }
            }

            "filter" -> {
                if (exp.size >= 2) filterWords(exp[1], exp[0], include = true) else ""
            }

            "filter-out" -> {
                if (exp.size >= 2) filterWords(exp[1], exp[0], include = false) else ""
            }

            "findstring" -> {
                if (exp.size >= 2 && exp[0] in exp[1]) exp[0] else ""
            }

            "words" -> {
                if (exp.isNotEmpty()) {
                    exp[0].split(Regex("[ \t]+")).count { it.isNotEmpty() }.toString()
                } else {
                    "0"
                }
            }

            "word" -> {
                if (exp.size >= 2) {
                    val n = exp[0].toIntOrNull() ?: 0
                    val ws = exp[1].split(Regex("[ \t]+")).filter { it.isNotEmpty() }
                    if (n in 1..ws.size) ws[n - 1] else ""
                } else {
                    ""
                }
            }

            "firstword" -> {
                exp.firstOrNull()?.split(Regex("[ \t]+"))?.firstOrNull { it.isNotEmpty() } ?: ""
            }

            "lastword" -> {
                exp.firstOrNull()?.split(Regex("[ \t]+"))?.lastOrNull { it.isNotEmpty() } ?: ""
            }

            "wordlist" -> {
                if (exp.size >= 3) {
                    val s = exp[0].toIntOrNull() ?: 0
                    val e = exp[1].toIntOrNull() ?: 0
                    val ws = exp[2].split(Regex("[ \t]+")).filter { it.isNotEmpty() }
                    if (s in 1..ws.size && e >= s) ws.subList(s - 1, minOf(e, ws.size)).joinToString(" ") else ""
                } else {
                    ""
                }
            }

            "dir" -> {
                exp
                    .firstOrNull()
                    ?.split(Regex("[ \t]+"))
                    ?.filter { it.isNotEmpty() }
                    ?.joinToString(" ") { p ->
                        p.substringBeforeLast('/', missingDelimiterValue = "") +
                            if ('/' in p) "/" else "./"
                    }
                    ?: ""
            }

            "notdir" -> {
                exp
                    .firstOrNull()
                    ?.split(Regex("[ \t]+"))
                    ?.filter { it.isNotEmpty() }
                    ?.joinToString(" ") { p -> p.substringAfterLast('/') } ?: ""
            }

            "basename" -> {
                exp
                    .firstOrNull()
                    ?.split(Regex("[ \t]+"))
                    ?.filter { it.isNotEmpty() }
                    ?.joinToString(" ") { p ->
                        val slash = p.lastIndexOf('/')
                        val dot = p.lastIndexOf('.')
                        if (dot > slash) p.substring(0, dot) else p
                    } ?: ""
            }

            "suffix" -> {
                exp
                    .firstOrNull()
                    ?.split(Regex("[ \t]+"))
                    ?.filter { it.isNotEmpty() }
                    ?.mapNotNull { p ->
                        val slash = p.lastIndexOf('/')
                        val dot = p.lastIndexOf('.')
                        if (dot > slash) p.substring(dot) else null
                    }?.joinToString(" ") ?: ""
            }

            "addprefix" -> {
                if (exp.size >= 2) {
                    val prefix = exp[0]
                    exp[1]
                        .split(Regex("[ \t]+"))
                        .filter { it.isNotEmpty() }
                        .joinToString(" ") { prefix + it }
                } else {
                    ""
                }
            }

            "addsuffix" -> {
                if (exp.size >= 2) {
                    val suffix = exp[0]
                    exp[1]
                        .split(Regex("[ \t]+"))
                        .filter { it.isNotEmpty() }
                        .joinToString(" ") { it + suffix }
                } else {
                    ""
                }
            }

            "join" -> {
                if (exp.size >= 2) {
                    val a = exp[0].split(Regex("[ \t]+")).filter { it.isNotEmpty() }
                    val b = exp[1].split(Regex("[ \t]+")).filter { it.isNotEmpty() }
                    val n = maxOf(a.size, b.size)
                    (0 until n).joinToString(" ") { i -> (a.getOrNull(i) ?: "") + (b.getOrNull(i) ?: "") }
                } else {
                    ""
                }
            }

            "if" -> {
                val cond = exp.getOrNull(0)?.trim()?.isNotEmpty() == true
                if (cond) exp.getOrNull(1) ?: "" else exp.getOrNull(2) ?: ""
            }

            "or" -> {
                exp.firstOrNull { it.isNotEmpty() } ?: ""
            }

            "and" -> {
                if (exp.isEmpty()) {
                    ""
                } else if (exp.any { it.isEmpty() }) {
                    ""
                } else {
                    exp.last()
                }
            }

            "origin" -> {
                val v = env.get(exp.firstOrNull() ?: "")
                when (v?.origin) {
                    null -> "undefined"
                    MacroOrigin.DEFAULT -> "default"
                    MacroOrigin.ENVIRONMENT -> "environment"
                    MacroOrigin.FILE -> "file"
                    MacroOrigin.COMMAND_LINE -> "command line"
                    MacroOrigin.OVERRIDE -> "override"
                    MacroOrigin.AUTOMATIC -> "automatic"
                }
            }

            "flavor" -> {
                val v = env.get(exp.firstOrNull() ?: "")
                when (v?.flavor) {
                    null -> "undefined"
                    MacroFlavor.RECURSIVE -> "recursive"
                    MacroFlavor.IMMEDIATE, MacroFlavor.IMMEDIATE_TRIPLE -> "simple"
                    else -> "recursive"
                }
            }

            else -> {
                ""
            }
        }
    }

    private fun filterWords(
        text: String,
        patterns: String,
        include: Boolean,
    ): String {
        val pats = patterns.split(Regex("[ \t]+")).filter { it.isNotEmpty() }
        val words = text.split(Regex("[ \t]+")).filter { it.isNotEmpty() }
        return words
            .filter { w ->
                val matched = pats.any { p -> patternMatch(p, w) }
                if (include) matched else !matched
            }.joinToString(" ")
    }

    private fun patternMatch(
        pat: String,
        word: String,
    ): Boolean {
        val pct = pat.indexOf('%')
        if (pct < 0) return pat == word
        val prefix = pat.substring(0, pct)
        val suffix = pat.substring(pct + 1)
        return word.startsWith(prefix) &&
            word.endsWith(suffix) &&
            word.length >= prefix.length + suffix.length
    }

    private fun splitArgs(
        text: String,
        expected: Int,
    ): List<String> {
        val args = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '$' && i + 1 < text.length && (text[i + 1] == '(' || text[i + 1] == '{')) {
                val open = text[i + 1]
                val close = if (open == '(') ')' else '}'
                val end = findMatchingClose(text, i + 2, open, close)
                if (end < 0) return listOf(text)
                i = end + 1
                continue
            }
            if (c == '(' || c == '{') {
                depth++
            } else if (c == ')' || c == '}') {
                depth--
            }
            if (c == ',' && depth == 0 && (expected < 0 || args.size < expected - 1)) {
                args += text.substring(start, i)
                start = i + 1
            }
            i++
        }
        args += text.substring(start)
        return args
    }

    companion object {
        val BUILTIN_FUNCS =
            setOf(
                "subst",
                "patsubst",
                "strip",
                "filter",
                "filter-out",
                "findstring",
                "words",
                "word",
                "firstword",
                "lastword",
                "wordlist",
                "dir",
                "notdir",
                "basename",
                "suffix",
                "addprefix",
                "addsuffix",
                "join",
                "if",
                "or",
                "and",
                "origin",
                "flavor",
            )

        val ARG_COUNTS =
            mapOf(
                "subst" to 3,
                "patsubst" to 3,
                "strip" to 1,
                "filter" to 2,
                "filter-out" to 2,
                "findstring" to 2,
                "words" to 1,
                "word" to 2,
                "firstword" to 1,
                "lastword" to 1,
                "wordlist" to 3,
                "dir" to 1,
                "notdir" to 1,
                "basename" to 1,
                "suffix" to 1,
                "addprefix" to 2,
                "addsuffix" to 2,
                "join" to 2,
                "if" to 3,
                "or" to -1,
                "and" to -1,
                "origin" to 1,
                "flavor" to 1,
            )
    }
}
