package com.accucodeai.kash.antlr

import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.misc.ParseCancellationException

/**
 * Structured form of a parser failure's location, suitable for composing a
 * language-specific diagnostic message. Position fields are 1-based for the
 * line and 0-based for the column — matching ANTLR's own conventions.
 */
public data class AntlrDiagnostic(
    val line: Int,
    val column: Int,
    /** Raw text of the offending token, or `null` if not identifiable. */
    val offendingText: String?,
)

/**
 * Extract a [AntlrDiagnostic] from a [RecognitionException]. Both fields fall
 * back to `0` / `null` when ANTLR couldn't pin them down (some
 * `NoViableAltException` cases rewind the offending token).
 */
public fun RecognitionException.toAntlrDiagnostic(): AntlrDiagnostic {
    val tok = offendingToken
    return AntlrDiagnostic(
        line = tok?.line ?: 0,
        column = tok?.charPositionInLine ?: 0,
        offendingText = tok?.text,
    )
}

/**
 * Best-effort diagnostic extraction across the three exception shapes a
 * fail-fast ANTLR pipeline can produce: parser-side
 * [RecognitionException], the [ParseCancellationException] thrown by
 * `BailErrorStrategy` (which usually wraps a [RecognitionException]), and
 * [AntlrSyntaxException] (lexer side, via [ThrowingErrorListener]).
 */
public fun Throwable.toAntlrDiagnostic(): AntlrDiagnostic =
    when (this) {
        is RecognitionException -> {
            toAntlrDiagnostic()
        }

        is AntlrSyntaxException -> {
            AntlrDiagnostic(line, charPositionInLine, null)
        }

        is ParseCancellationException -> {
            (cause as? RecognitionException)?.toAntlrDiagnostic()
                ?: AntlrDiagnostic(0, 0, null)
        }

        else -> {
            AntlrDiagnostic(0, 0, null)
        }
    }

/**
 * Normalize an offending token's text for display in a diagnostic. Maps
 * `<EOF>` and blank tokens to `"newline"` (the conventional bash/jq
 * presentation), passes everything else through unchanged. Returns `null`
 * if the source text was `null`.
 */
public fun displayToken(text: String?): String? =
    when {
        text == null -> null
        text == "<EOF>" || text.isBlank() -> "newline"
        else -> text
    }
