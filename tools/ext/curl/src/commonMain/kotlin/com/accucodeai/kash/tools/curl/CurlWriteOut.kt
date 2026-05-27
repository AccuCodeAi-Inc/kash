package com.accucodeai.kash.tools.curl

/**
 * Render a curl `-w` / `--write-out` template. Supports the subset that
 * shell scripts actually use:
 *  - `%{http_code}` → status code as a decimal int
 *  - `%{url_effective}` → request URL (no redirect resolution today;
 *    matches the final hop curl actually fetched if `-L` is in play)
 *  - `%{size_download}` → response body byte count
 *  - `%{content_type}` → `Content-Type` header (empty if absent)
 *  - Backslash escapes `\n` / `\t` / `\r` / `\\`
 *
 * Unknown `%{name}` placeholders are emitted verbatim (curl's behavior).
 * Takes primitives so it's callable from the streaming path — the
 * response body has already been written by the time we render this, so
 * we couldn't pass an `HttpResponse` even if one still existed.
 */
internal fun renderWriteOut(
    fmt: String,
    url: String,
    status: Int,
    headers: List<Pair<String, String>>,
    sizeDownload: Long,
): String {
    val sb = StringBuilder()
    var i = 0
    while (i < fmt.length) {
        val c = fmt[i]
        when {
            c == '\\' && i + 1 < fmt.length -> {
                when (val n = fmt[i + 1]) {
                    'n' -> {
                        sb.append('\n')
                    }

                    't' -> {
                        sb.append('\t')
                    }

                    'r' -> {
                        sb.append('\r')
                    }

                    '\\' -> {
                        sb.append('\\')
                    }

                    else -> {
                        sb.append(c)
                        sb.append(n)
                    }
                }
                i += 2
            }

            c == '%' && i + 1 < fmt.length && fmt[i + 1] == '{' -> {
                val end = fmt.indexOf('}', i + 2)
                if (end < 0) {
                    sb.append(c)
                    i++
                } else {
                    val key = fmt.substring(i + 2, end)
                    sb.append(resolveVar(key, url, status, headers, sizeDownload, fmt, i, end + 1))
                    i = end + 1
                }
            }

            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

private fun resolveVar(
    key: String,
    url: String,
    status: Int,
    headers: List<Pair<String, String>>,
    sizeDownload: Long,
    fmt: String,
    rawStart: Int,
    rawEnd: Int,
): String =
    when (key) {
        "http_code" -> status.toString()
        "url_effective" -> url
        "size_download" -> sizeDownload.toString()
        "content_type" -> headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second ?: ""
        else -> fmt.substring(rawStart, rawEnd) // pass-through unknown
    }
