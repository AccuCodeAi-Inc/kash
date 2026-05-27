// Lexer for POSIX awk.
//
// Awk has exactly one context-sensitive token: `/`. The same source
// characters `/foo/` mean either "regex literal" or "divide, identifier
// foo, divide" depending on whether the previous token could end an
// operand. POSIX phrases it as: a `/` introduces a regex literal *except*
// in positions where the division operator could also appear (i.e.,
// immediately after an operand: identifier, number, string, `)`, `]`,
// post-increment, post-decrement, `$expr`).
//
// We resolve this lexer-side by tracking a single `regexAllowed` flag:
//
//  - After any "operand-ending" token (IDENT, NUMBER, STRING, RPAREN,
//    RBRACK, INC, DEC, REGEX, DOLLAR is *not* operand-ending — `$` is
//    always followed by a primary), `regexAllowed` is `false`.
//  - After everything else (operators, keywords, separators), it's `true`.
//  - At input start, it's `true` (so a program like `/foo/` parses as
//    a regex pattern with no action).
//
// The flag is updated by overriding `emit()` so every emitted token gets
// classified centrally — that's safer than per-rule actions, which are
// easy to forget when adding new tokens.
//
// The REGEX / DIV / DIVEQ rules then use semantic predicates on the flag.
// Ordering matters: at regex-allowed positions a `/=` introduces a regex
// matching `=…`, not the divide-assign operator. The predicate-guarded
// REGEX rule runs *before* DIVEQ/DIV so it wins by ANTLR's first-match
// preference when both could fire.
lexer grammar AwkLexer;

@lexer::members {
    private var regexAllowed: Boolean = true

    override fun emit(): org.antlr.v4.kotlinruntime.Token {
        val t = super.emit()
        // Tokens that end an operand: a following `/` is division.
        regexAllowed = when (t.type) {
            Tokens.IDENT,
            Tokens.NUMBER,
            Tokens.STRING,
            Tokens.REGEX,
            Tokens.RPAREN,
            Tokens.RBRACK,
            Tokens.INC,
            Tokens.DEC,
            -> false
            else -> true
        }
        return t
    }
}

// Whitespace and comments. Backslash-newline is line continuation (joined
// silently like in bash); a bare newline is significant (statement
// terminator).
WS              : [ \t]+ -> skip ;
LINE_CONT       : '\\' '\r'? '\n' -> skip ;
COMMENT         : '#' ~[\r\n]* -> skip ;
NEWLINE         : '\r'? '\n' ;

// Keywords (must precede IDENT). POSIX list, plus the `printf` distinct
// keyword (some impls treat it as a builtin; awk grammar has it as a
// keyword because the print/printf statement form is special-cased).
KW_BEGIN        : 'BEGIN' ;
KW_END          : 'END' ;
KW_IF           : 'if' ;
KW_ELSE         : 'else' ;
KW_WHILE        : 'while' ;
KW_FOR          : 'for' ;
KW_DO           : 'do' ;
KW_BREAK        : 'break' ;
KW_CONTINUE     : 'continue' ;
KW_NEXT         : 'next' ;
KW_NEXTFILE     : 'nextfile' ;
KW_EXIT         : 'exit' ;
KW_RETURN       : 'return' ;
KW_FUNCTION     : 'function' ;
KW_FUNC         : 'func' ;          // gawk synonym; harmless to accept
KW_DELETE       : 'delete' ;
KW_IN           : 'in' ;
KW_GETLINE      : 'getline' ;
KW_PRINT        : 'print' ;
KW_PRINTF       : 'printf' ;

// Numeric literal: integer, float, scientific. The leading-dot form
// `.5` is permitted by POSIX awk too.
NUMBER
    : [0-9]+ ('.' [0-9]*)? EXP?
    | '.' [0-9]+ EXP?
    ;
fragment EXP    : [eE] [+-]? [0-9]+ ;

// Strings: double-quoted, with the POSIX awk escape set. Escape *resolution*
// lives in the AST builder so the lexer doesn't need to know the final byte
// values — it just preserves the raw text.
STRING          : '"' ( '\\' . | ~["\\\r\n] )* '"' ;

// Regex literal — guarded so it only matches at "regex-allowed" positions.
// Note `~[/\r\n\\]` excludes the closer, line endings, and backslash; the
// backslash branch lets `\/` survive intact.
REGEX           : { regexAllowed }? '/' ( '\\' . | ~[/\r\n\\] )* '/' ;

// Division operators, only available at non-regex positions. Order is
// important: ANTLR's max-munch picks `/=` over `/` when both could match.
DIVEQ           : { !regexAllowed }? '/=' ;
DIV             : { !regexAllowed }? '/' ;

// Compound assignment + comparison + match operators. Longest-prefix wins
// by max-munch, so `==` beats `=`, `<=` beats `<`, etc.
PLUSEQ          : '+=' ;
MINUSEQ         : '-=' ;
MULTEQ          : '*=' ;
MODEQ           : '%=' ;
POWEQ           : ( '^=' | '**=' ) ;
EQEQ            : '==' ;
NEQ             : '!=' ;
LE              : '<=' ;
GE              : '>=' ;
LT              : '<' ;
GT              : '>' ;
APPEND          : '>>' ;
ANDAND          : '&&' ;
OROR            : '||' ;
INC             : '++' ;
DEC             : '--' ;
MATCH           : '~' ;
NOMATCH         : '!~' ;
NOT             : '!' ;
ASSIGN          : '=' ;
PLUS            : '+' ;
MINUS           : '-' ;
STAR            : '*' ;
MOD             : '%' ;
POW             : ( '^' | '**' ) ;
LPAREN          : '(' ;
RPAREN          : ')' ;
LBRACK          : '[' ;
RBRACK          : ']' ;
LBRACE          : '{' ;
RBRACE          : '}' ;
COMMA           : ',' ;
SEMI            : ';' ;
QUESTION        : '?' ;
COLON           : ':' ;
DOLLAR          : '$' ;
PIPE            : '|' ;

// Identifiers and field-marker. POSIX awk names: letter or underscore,
// then letters/digits/underscores.
IDENT           : [A-Za-z_] [A-Za-z_0-9]* ;
