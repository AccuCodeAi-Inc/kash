package com.accucodeai.kash.tools.curl

/** Thrown by [parseArgs] for any flag/value problem. Message is user-facing. */
internal class CurlArgError(
    message: String,
) : RuntimeException(message)

internal data class CurlOpts(
    val urls: List<String>,
    val method: String?,
    val headers: List<Pair<String, String>>,
    val body: ByteArray?,
    val output: String?,
    val useRemoteName: Boolean,
    val includeHeaders: Boolean,
    val headOnly: Boolean,
    val followRedirects: Boolean,
    val failOnError: Boolean,
    val userAgent: String?,
    val referer: String?,
    val basicAuth: String?,
    val writeOut: String?,
)

internal fun parseArgs(args: List<String>): CurlOpts {
    val urls = mutableListOf<String>()
    val headers = mutableListOf<Pair<String, String>>()
    var method: String? = null
    var body: ByteArray? = null
    var output: String? = null
    var useRemoteName = false
    var includeHeaders = false
    var headOnly = false
    var followRedirects = false
    var failOnError = false
    var userAgent: String? = null
    var referer: String? = null
    var basicAuth: String? = null
    var writeOut: String? = null

    var i = 0

    fun next(flag: String): String {
        i++
        if (i >= args.size) throw CurlArgError("option $flag requires an argument")
        return args[i]
    }

    while (i < args.size) {
        val a = args[i]
        when {
            a == "--" -> {
                i++
                while (i < args.size) {
                    urls += args[i]
                    i++
                }
                return build(
                    urls,
                    method,
                    headers,
                    body,
                    output,
                    useRemoteName,
                    includeHeaders,
                    headOnly,
                    followRedirects,
                    failOnError,
                    userAgent,
                    referer,
                    basicAuth,
                    writeOut,
                )
            }

            !a.startsWith("-") || a == "-" -> {
                urls += a
            }

            a == "-X" || a == "--request" -> {
                method = next(a)
            }

            a == "-H" || a == "--header" -> {
                headers += splitHeader(next(a))
            }

            a == "-d" || a == "--data" || a == "--data-raw" || a == "--data-binary" || a == "--data-ascii" -> {
                val v = next(a).encodeToByteArray()
                body = if (body == null) v else body + v
            }

            a == "-o" || a == "--output" -> {
                output = next(a)
            }

            a == "-O" || a == "--remote-name" -> {
                useRemoteName = true
            }

            a == "-i" || a == "--include" -> {
                includeHeaders = true
            }

            a == "-I" || a == "--head" -> {
                headOnly = true
            }

            a == "-s" || a == "--silent" -> {}

            // no-op (no progress meter to silence)
            a == "-S" || a == "--show-error" -> {}

            // no-op (errors always go to stderr)
            a == "-L" || a == "--location" -> {
                followRedirects = true
            }

            a == "-f" || a == "--fail" -> {
                failOnError = true
            }

            a == "-A" || a == "--user-agent" -> {
                userAgent = next(a)
            }

            a == "-e" || a == "--referer" -> {
                referer = next(a)
            }

            a == "-u" || a == "--user" -> {
                basicAuth = next(a)
            }

            a == "-w" || a == "--write-out" -> {
                writeOut = next(a)
            }

            a == "-m" || a == "--max-time" -> {
                next(a) // parsed; not yet enforced
            }

            a == "--connect-timeout" -> {
                next(a)
            }

            a == "--url" -> {
                urls += next(a)
            }

            a == "--help" -> {
                throw CurlArgError("--help is not implemented; see kash docs")
            }

            a.startsWith("--") -> {
                throw CurlArgError("unsupported option: $a")
            }

            a.startsWith("-") && a.length > 2 -> {
                // bundled short flags: -sSL, -fL, -is
                for (c in a.drop(1)) {
                    when (c) {
                        's' -> {}

                        'S' -> {}

                        'L' -> {
                            followRedirects = true
                        }

                        'f' -> {
                            failOnError = true
                        }

                        'i' -> {
                            includeHeaders = true
                        }

                        'I' -> {
                            headOnly = true
                        }

                        'O' -> {
                            useRemoteName = true
                        }

                        else -> {
                            throw CurlArgError("unsupported short option: -$c (in $a)")
                        }
                    }
                }
            }

            else -> {
                throw CurlArgError("unsupported option: $a")
            }
        }
        i++
    }

    return build(
        urls,
        method,
        headers,
        body,
        output,
        useRemoteName,
        includeHeaders,
        headOnly,
        followRedirects,
        failOnError,
        userAgent,
        referer,
        basicAuth,
        writeOut,
    )
}

@Suppress("LongParameterList")
private fun build(
    urls: List<String>,
    method: String?,
    headers: List<Pair<String, String>>,
    body: ByteArray?,
    output: String?,
    useRemoteName: Boolean,
    includeHeaders: Boolean,
    headOnly: Boolean,
    followRedirects: Boolean,
    failOnError: Boolean,
    userAgent: String?,
    referer: String?,
    basicAuth: String?,
    writeOut: String?,
): CurlOpts =
    CurlOpts(
        urls = urls,
        method = method,
        headers = headers,
        body = body,
        output = output,
        useRemoteName = useRemoteName,
        includeHeaders = includeHeaders,
        headOnly = headOnly,
        followRedirects = followRedirects,
        failOnError = failOnError,
        userAgent = userAgent,
        referer = referer,
        basicAuth = basicAuth,
        writeOut = writeOut,
    )

private fun splitHeader(s: String): Pair<String, String> {
    val colon = s.indexOf(':')
    if (colon < 0) throw CurlArgError("malformed header (no colon): $s")
    val name = s.substring(0, colon).trim()
    val value = s.substring(colon + 1).trim()
    return name to value
}
