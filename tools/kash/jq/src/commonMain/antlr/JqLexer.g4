// Lexer for the jq query language.
//
// The interesting part is string interpolation: `"foo \(expr) bar"` is
// tokenised as STRING_START STRING_TEXT STRING_INTERP_START <expr tokens>
// STRING_INTERP_END STRING_TEXT STRING_END.
//
// To know which `)` closes the interpolation, the lexer tracks a stack of
// "paren depths". Each `\(` pushes 0; LPAREN/RPAREN in DEFAULT_MODE
// inc/dec the top of the stack while the stack is non-empty. When an RPAREN
// would take the top below zero, it's the closer for the interpolation —
// the stack pops and the token re-types to STRING_INTERP_END, popping the
// lexer mode back to STR_MODE so the rest of the string body is lexed.
//
// (Stack-of-counters is needed rather than a single counter because nested
// interpolations are legal — `"a\("b\(c)d")e"` is a real jq construct.)
//
// Escape processing for STRING_TEXT runs (\n, \t, \uXXXX, …) happens in the
// AstBuilder, not here — ANTLR-Kotlin's lexer doesn't have an ergonomic way
// to rewrite token text mid-lex, and the parser doesn't care about the
// actual characters anyway.
lexer grammar JqLexer;

@lexer::header {
import kotlin.collections.ArrayDeque
}

@lexer::members {
    private val interpParenDepth: ArrayDeque<Int> = ArrayDeque()
}

// Forward-declared so the RPAREN action below can refer to STRING_INTERP_END
// (which is otherwise only defined inside STR_MODE's STRING_INTERP_START
// rule) and so `type(STRING_TEXT)` in STR_MODE doesn't need a separate
// declaration.
tokens { STRING_INTERP_END, STRING_TEXT }

// --- whitespace + comments ---
WS      : [ \t\r\n]+      -> skip ;
COMMENT : '#' ~[\r\n]*    -> skip ;

// --- numbers and identifiers ---
NUMBER  : [0-9]+ ('.' [0-9]+)? ([eE] [+-]? [0-9]+)? ;

DOT_DOT : '..' ;
DOT     : '.' ;

// Operator order matters when prefixes overlap. ANTLR picks the longest
// match, but ties go to the rule listed first; '//=' beats '//' which beats
// '/=' which beats '/'.
SETALT   : '//=' ;
ALT      : '//' ;
SETPIPE  : '|=' ;
PIPE     : '|' ;
SETPLUS  : '+=' ;
PLUS     : '+' ;
SETMINUS : '-=' ;
MINUS    : '-' ;
SETMULT  : '*=' ;
STAR     : '*' ;
SETDIV   : '/=' ;
SLASH    : '/' ;
SETMOD   : '%=' ;
PERCENT  : '%' ;
EQ_EQ    : '==' ;
ASSIGN   : '=' ;
NEQ      : '!=' ;
LE       : '<=' ;
LT       : '<' ;
GE       : '>=' ;
GT       : '>' ;

QUESTION : '?' ;
COMMA    : ',' ;
COLON    : ':' ;
SEMI     : ';' ;
LBRACK   : '[' ;
RBRACK   : ']' ;
LBRACE   : '{' ;
RBRACE   : '}' ;

LPAREN : '(' {
    if (interpParenDepth.isNotEmpty()) {
        interpParenDepth[interpParenDepth.size - 1] = interpParenDepth.last() + 1
    }
};

RPAREN : ')' {
    if (interpParenDepth.isNotEmpty()) {
        val d = interpParenDepth.last()
        if (d == 0) {
            interpParenDepth.removeLast()
            type = Tokens.STRING_INTERP_END
            popMode()
        } else {
            interpParenDepth[interpParenDepth.size - 1] = d - 1
        }
    }
};

// --- keywords (must precede IDENT) ---
KW_IF      : 'if' ;
KW_THEN    : 'then' ;
KW_ELIF    : 'elif' ;
KW_ELSE    : 'else' ;
KW_END     : 'end' ;
KW_AND     : 'and' ;
KW_OR      : 'or' ;
KW_NOT     : 'not' ;
KW_TRY     : 'try' ;
KW_CATCH   : 'catch' ;
KW_AS      : 'as' ;
KW_REDUCE  : 'reduce' ;
KW_FOREACH : 'foreach' ;
KW_DEF     : 'def' ;
KW_NULL    : 'null' ;
KW_TRUE    : 'true' ;
KW_FALSE   : 'false' ;

IDENT  : [a-zA-Z_] [a-zA-Z_0-9]* ;
VAR    : '$' [a-zA-Z_] [a-zA-Z_0-9]* ;
FORMAT : '@' [a-zA-Z_0-9]* ;

// String literals: switch to STR_MODE so '"' and '\(' get their special
// meaning.
STRING_START : '"' -> pushMode(STR_MODE) ;

// --- inside a string literal ---
mode STR_MODE;

STRING_END : '"' -> popMode ;

// '\(' opens an interpolation. Push a fresh 0 onto the depth stack so the
// next RPAREN at depth 0 in DEFAULT_MODE knows it's the closer.
STRING_INTERP_START : '\\(' {
    interpParenDepth.addLast(0)
} -> pushMode(DEFAULT_MODE) ;

// One run of literal text, possibly including escape sequences other than
// '\(' (which is the interpolation opener). The AstBuilder turns the raw
// characters into the final string payload (resolving \n, \t, \uXXXX, …).
//
// `'\\' ~'('` is the load-bearing detail: with the looser `'\\' .` ANTLR's
// max-munch makes a long STRING_TEXT_RUN swallow `\(...)` because it can
// extend the run further than STRING_INTERP_START's 2 characters.
STRING_TEXT_RUN : ( '\\' ~'(' | ~["\\] )+ -> type(STRING_TEXT) ;
