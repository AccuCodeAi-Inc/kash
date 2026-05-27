// Parser grammar for jq.
//
// Mirrors the precedence ladder the hand-written parser implemented:
//
//   pipe  (right-assoc)
//   comma (left-assoc)
//   assign (right-assoc, nonassoc with each other)
//   as    (right-assoc; binds to the *trailing* pipe via `as $v |` syntax)
//   alt   `//` (left-assoc)
//   or    (left-assoc)
//   and   (left-assoc)
//   cmp   (nonassoc)
//   add/sub (left-assoc)
//   mul/div/mod (left-assoc)
//   unary minus
//   postfix (`.field`, `[idx]`, `?` chains)
//   term  (primaries: literals, `.`, `..`, control forms, function calls, …)
//
// `try`, `reduce`, `foreach` take a *postfix* expression, not a full pipe —
// `try a | b` parses as `(try a) | b`. That's why those alternatives recurse
// into `postfix` rather than `pipe`.
//
// We do NOT use ANTLR's left-recursion machinery: the layered rules are
// closer to the existing hand parser and translate to the visitor's
// per-rule overrides one-to-one.
parser grammar JqParser;

options { tokenVocab = JqLexer; }

program : pipe EOF ;

pipe    : comma (PIPE pipe)? ;
comma   : assign (COMMA assign)* ;

assign   : asExpr (assignOp asExpr)? ;
assignOp : ASSIGN | SETPIPE | SETPLUS | SETMINUS | SETMULT | SETDIV | SETMOD | SETALT ;

asExpr  : alt (KW_AS VAR PIPE pipe)? ;
alt     : orExpr (ALT orExpr)* ;
orExpr  : andExpr (KW_OR andExpr)* ;
andExpr : cmp (KW_AND cmp)* ;

cmp     : addExpr (cmpOp addExpr)? ;
cmpOp   : EQ_EQ | NEQ | LT | LE | GT | GE ;

addExpr : mulExpr (addOp mulExpr)* ;
addOp   : PLUS | MINUS ;

mulExpr : unary (mulOp unary)* ;
mulOp   : STAR | SLASH | PERCENT ;

unary
    : MINUS unary           # UnaryNeg
    | postfix               # UnaryPostfix
    ;

postfix : term postOp* ;

postOp
    : DOT IDENT QUESTION?                       # PostField
    | LBRACK indexBody RBRACK QUESTION?         # PostIndex
    | QUESTION                                  # PostOpt
    ;

indexBody
    : /* empty: iterate */                                  # IndexIterate
    | COLON pipe                                            # IndexSliceTo
    | pipe COLON pipe?                                      # IndexSliceFrom
    | pipe                                                  # IndexAt
    ;

term
    : DOT IDENT QUESTION?                       # TermDotField
    | DOT LBRACK indexBody RBRACK QUESTION?     # TermDotIndex
    | DOT stringLit                             # TermDotString
    | DOT                                       # TermIdentity
    | DOT_DOT                                   # TermRecurse
    | NUMBER                                    # TermNumber
    | KW_NULL                                   # TermNull
    | KW_TRUE                                   # TermTrue
    | KW_FALSE                                  # TermFalse
    | stringLit                                 # TermString
    | VAR                                       # TermVar
    | LPAREN pipe RPAREN                        # TermParen
    | LBRACK pipe? RBRACK                       # TermArrayLit
    | LBRACE objEntries? RBRACE                 # TermObjectLit
    | ifExpr                                    # TermIf
    | tryExpr                                   # TermTry
    | reduceExpr                                # TermReduce
    | foreachExpr                               # TermForeach
    | defExpr                                   # TermDef
    | KW_NOT                                    # TermNot
    | IDENT (LPAREN funcArgs RPAREN)?           # TermFuncCall
    | FORMAT                                    # TermFormat
    ;

stringLit
    : STRING_START stringPart* STRING_END
    ;

stringPart
    : STRING_TEXT                                           # StringPartText
    | STRING_INTERP_START pipe STRING_INTERP_END            # StringPartInterp
    ;

objEntries : objEntry (COMMA objEntry)* ;

objEntry
    : IDENT       (COLON objValue)?      # ObjEntryIdent
    | stringLit   COLON objValue          # ObjEntryString
    | VAR                                 # ObjEntryVar
    | LPAREN pipe RPAREN COLON objValue   # ObjEntryParen
    ;

// Object values can't contain bare `,` (that's the entry separator), so
// they bottom out at the `alt` precedence layer — same as the hand parser.
objValue : alt ;

ifExpr
    : KW_IF pipe KW_THEN pipe (KW_ELIF pipe KW_THEN pipe)* (KW_ELSE pipe)? KW_END
    ;

tryExpr
    : KW_TRY postfix (KW_CATCH postfix)?
    ;

reduceExpr
    : KW_REDUCE postfix KW_AS VAR LPAREN pipe SEMI pipe RPAREN
    ;

foreachExpr
    : KW_FOREACH postfix KW_AS VAR LPAREN pipe SEMI pipe (SEMI pipe)? RPAREN
    ;

defExpr
    : KW_DEF IDENT (LPAREN defParams RPAREN)? COLON pipe SEMI pipe
    ;

defParams : defParam (SEMI defParam)* ;
defParam  : IDENT | VAR ;

funcArgs  : pipe (SEMI pipe)* ;
