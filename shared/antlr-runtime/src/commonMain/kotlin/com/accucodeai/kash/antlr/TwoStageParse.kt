package com.accucodeai.kash.antlr

import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.Parser
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.atn.PredictionMode
import org.antlr.v4.kotlinruntime.misc.ParseCancellationException

/**
 * Two-stage parsing strategy from the ANTLR `ParserATNSimulator` KDoc:
 *
 *  - **Stage 1 — SLL.** Fastest mode; either returns the same tree LL would
 *    or throws on the first conflict.
 *  - **Stage 2 — LL.**  Run only when SLL gave up; handles inputs that need
 *    full parser-context prediction.
 *
 * Without this split, the default LL mode falls into
 * `execATNWithFullContext` on every SLL conflict in any non-trivial grammar
 * — observable as seconds-to-minutes parse times.
 *
 * This overload is for the **whole-input** case: on retry the stream rewinds
 * to position 0 and the parser is reset, so the entry rule starts over from
 * scratch. The parser is assumed to already be configured via
 * [configureForFailFast].
 */
public inline fun <T> twoStageParse(
    parser: Parser,
    stream: CommonTokenStream,
    parse: () -> T,
): T =
    try {
        parse()
    } catch (e: ParseCancellationException) {
        runWithLL(parser, stream, rewindTo = 0, parse, restoreSll = false, cause = e)
    } catch (e: RecognitionException) {
        runWithLL(parser, stream, rewindTo = 0, parse, restoreSll = false, cause = e)
    } catch (e: AntlrSyntaxException) {
        runWithLL(parser, stream, rewindTo = 0, parse, restoreSll = false, cause = e)
    }

/**
 * Streaming variant of [twoStageParse]. Use this when the caller is
 * consuming the input rule-by-rule in a loop (e.g. statement-streaming) and
 * cannot rewind to position 0 — only to the start of the current rule.
 *
 * Restores `PredictionMode.SLL` in a `finally` so the *next* iteration of
 * the caller's loop starts back on the fast path. Without that, a single
 * SLL-conflicting rule would pin the parser to LL mode for the remainder
 * of the input.
 */
public inline fun <T> twoStageParseFrom(
    parser: Parser,
    stream: CommonTokenStream,
    mark: Int,
    parse: () -> T,
): T =
    try {
        parse()
    } catch (e: ParseCancellationException) {
        runWithLL(parser, stream, rewindTo = mark, parse, restoreSll = true, cause = e)
    } catch (e: RecognitionException) {
        runWithLL(parser, stream, rewindTo = mark, parse, restoreSll = true, cause = e)
    } catch (e: AntlrSyntaxException) {
        runWithLL(parser, stream, rewindTo = mark, parse, restoreSll = true, cause = e)
    }

@PublishedApi
internal inline fun <T> runWithLL(
    parser: Parser,
    stream: CommonTokenStream,
    rewindTo: Int,
    parse: () -> T,
    restoreSll: Boolean,
    @Suppress("UNUSED_PARAMETER") cause: Throwable,
): T {
    // Order matters: `parser.reset()` itself calls `tokenStream.seek(0)`,
    // so we have to reset first and seek to the rewind point *after*. The
    // opposite order makes `rewindTo` a no-op for the streaming variant.
    parser.reset()
    stream.seek(rewindTo)
    parser.interpreter.predictionMode = PredictionMode.LL
    return try {
        parse()
    } finally {
        if (restoreSll) {
            parser.interpreter.predictionMode = PredictionMode.SLL
        }
    }
}
