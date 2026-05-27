// Lexer for Makefile syntax.
//
// Makefile lexical rules are genuinely fiddly:
//
//   * A TAB at column 0 starts a recipe line. The entire rest of the line
//     (with its `\<newline>` continuations preserved) is opaque text that
//     gets handed to the shell verbatim after macro expansion. Comments
//     inside that text are NOT make comments.
//   * Outside recipe lines, `#` to end of line is a comment, and `\#` is
//     a literal hash. Backslash-newline joins to a single space.
//   * Words are whitespace-separated runs of non-special characters, but
//     a `$(…)` / `${…}` / `$X` macro reference may sit inside a word with
//     no whitespace (`foo$(VAR)bar` is one word). The lexer captures the
//     whole word — macro expansion happens later on the raw text.
//
// Strategy: track `atLineStart` so a TAB seen as the very first character
// of a line yields RECIPE_LINE with the entire line text (tab included);
// the AST builder strips the leading tab.
lexer grammar MakefileLexer;

@lexer::members {
    private var atLineStart: Boolean = true

    override fun emit(): org.antlr.v4.kotlinruntime.Token {
        val t = super.emit()
        atLineStart = (t.type == Tokens.NEWLINE)
        return t
    }
}

RECIPE_LINE
    : { atLineStart }? '\t' ( '\\' '\r'? '\n' | ~[\r\n] )*
    ;

NEWLINE         : '\r'? '\n' ;

LINE_CONT       : '\\' '\r'? '\n' -> skip ;

WS              : [ \t]+ -> skip ;

COMMENT         : '#' ~[\r\n]* -> skip ;

KW_IFEQ         : 'ifeq' ;
KW_IFNEQ        : 'ifneq' ;
KW_IFDEF        : 'ifdef' ;
KW_IFNDEF       : 'ifndef' ;
KW_ELSE         : 'else' ;
KW_ENDIF        : 'endif' ;
KW_DEFINE       : 'define' ;
KW_ENDEF        : 'endef' ;
KW_INCLUDE      : 'include' ;
KW_DASH_INCLUDE : '-include' ;
KW_SINCLUDE     : 'sinclude' ;
KW_EXPORT       : 'export' ;
KW_UNEXPORT     : 'unexport' ;
KW_OVERRIDE     : 'override' ;

ASSIGN_TRIPLE   : ':::=' ;
ASSIGN_DOUBLE   : '::=' ;
ASSIGN_IMM      : ':=' ;
ASSIGN_DEFAULT  : '?=' ;
ASSIGN_APPEND   : '+=' ;
ASSIGN_SHELL    : '!=' ;
ASSIGN_EQ       : '=' ;

COLON           : ':' ;
SEMI            : ';' ;

// Words: at least one of (escape | macro-ref | normal). Macro-refs are
// matched with bracket-balanced sub-rules so `$(foo $(bar))` lexes as
// one word. We use ANTLR's `~[…]` to keep the inner-char set tight.
WORD
    : ( WORD_PIECE )+
    ;

fragment WORD_PIECE
    : '\\' .
    | '$$'
    | '$' '(' WORD_PAREN_BODY ')'
    | '$' '{' WORD_BRACE_BODY '}'
    | '$' ~[\r\n]
    | ~[ \t\r\n#:;=$\\]
    ;

fragment WORD_PAREN_BODY
    : ( '$' '(' WORD_PAREN_BODY ')'
      | '$' '{' WORD_BRACE_BODY '}'
      | '\\' .
      | ~[()\r\n] )*
    ;

fragment WORD_BRACE_BODY
    : ( '$' '(' WORD_PAREN_BODY ')'
      | '$' '{' WORD_BRACE_BODY '}'
      | '\\' .
      | ~[{}\r\n] )*
    ;
