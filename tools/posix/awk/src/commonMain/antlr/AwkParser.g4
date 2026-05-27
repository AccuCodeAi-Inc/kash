// Parser for POSIX awk.
//
// Layered precedence ladder (low → high) mirroring how the hand parsers in
// :core and :tools:jq are structured — no ANTLR left-recursion, every rule
// is a simple non-left-recursive form whose visit method maps 1:1 to an
// AST constructor.
//
// Precedence, lowest to highest (POSIX §A.3, normalized to our shape):
//   1.  assign     :=  `=` `+=` `-=` `*=` `/=` `%=` `^=`   (right-assoc)
//   2.  ternary    :=  `?:`                                 (right-assoc)
//   3.  logOr      :=  `||`                                 (left-assoc)
//   4.  logAnd     :=  `&&`                                 (left-assoc)
//   5.  in         :=  `expr in IDENT`                      (non-assoc)
//   6.  match      :=  `~` `!~`                             (left-assoc)
//   7.  rel        :=  `<` `<=` `==` `!=` `>` `>=`          (non-assoc)
//   8.  concat     :=  string concatenation by juxtaposition
//   9.  add        :=  `+` `-`                              (left-assoc)
//   10. mul        :=  `*` `/` `%`                          (left-assoc)
//   11. pow        :=  `^` `**`                             (right-assoc)
//   12. unary      :=  `!` unary | `-` unary | `+` unary    (right-assoc)
//   13. preIncDec  :=  `++` `--` prefix forms               (right-assoc)
//   14. field      :=  `$` expression
//   15. postfix    :=  postfix `++` `--`
//   16. primary    :=  literal | var | array | call | `(expr)` | getline
//
// Output redirection (`print a > file`) is parsed but the `>` in that
// position currently goes through `rel`, producing the wrong AST.
// Documented in STATUS.md (Slice 2 work); fixed when we add a separate
// `printable` expression form that excludes `>`/`>>`/`|` at the top
// level.
//
// `getline` is parsed at the primary level for the no-source / `<file`
// forms; the `cmd | getline` form would need bottom-up handling that
// conflicts with statement-level `|` for print redirection. Deferred.
parser grammar AwkParser;

options { tokenVocab = AwkLexer; }

// -- Entry ------------------------------------------------------------------

program : sep* ( item sep* )* EOF ;

// Item/statement separator: `;` or newline, freely intermixed and
// optionally absent between two action-block-terminated items (since `}`
// is a natural boundary). Empty `sep*` is also fine after an action
// block — that's why item iteration here is permissive: `BEGIN{}END{}`
// works on one line with no separator at all.
sep : SEMI | NEWLINE ;

// nl absorbs zero or more newlines wherever they're cosmetic.
nl : NEWLINE* ;

// -- Top-level items --------------------------------------------------------

item
    : functionDef                              # ItemFunction
    | rule                                      # ItemRule
    ;

functionDef
    : ( KW_FUNCTION | KW_FUNC ) IDENT LPAREN nl paramList? nl RPAREN nl actionBlock
    ;

paramList : IDENT ( COMMA nl IDENT )* ;

rule
    : pattern nl actionBlock                   # RuleWithAction
    | pattern                                   # RulePatternOnly
    | actionBlock                               # RuleActionOnly
    ;

pattern
    : KW_BEGIN                                  # PatBegin
    | KW_END                                    # PatEnd
    | expr COMMA nl expr                       # PatRange
    | expr                                      # PatExpr
    ;

actionBlock : LBRACE nl stmtList? nl RBRACE ;

// -- Statements -------------------------------------------------------------

stmtList
    : stmt ( sep+ stmt )* sep*
    | sep+
    ;

stmt
    : actionBlock                                                            # StmtBlock
    | KW_IF LPAREN expr RPAREN nl stmt ( sep* KW_ELSE nl stmt )?            # StmtIf
    | KW_WHILE LPAREN expr RPAREN nl stmt                                   # StmtWhile
    | KW_DO nl stmt sep* KW_WHILE LPAREN expr RPAREN                         # StmtDoWhile
    | KW_FOR LPAREN nl forSimpleInit? nl SEMI nl expr? nl SEMI nl forSimpleStep? nl RPAREN nl stmt   # StmtFor
    | KW_FOR LPAREN IDENT KW_IN IDENT RPAREN nl stmt                        # StmtForIn
    | KW_FOR LPAREN LPAREN exprList RPAREN KW_IN IDENT RPAREN nl stmt       # StmtForInMulti
    | KW_BREAK                                                               # StmtBreak
    | KW_CONTINUE                                                            # StmtContinue
    | KW_NEXT                                                                # StmtNext
    | KW_NEXTFILE                                                            # StmtNextfile
    | KW_EXIT expr?                                                          # StmtExit
    | KW_RETURN expr?                                                        # StmtReturn
    | KW_DELETE IDENT LBRACK exprList RBRACK                                 # StmtDeleteElem
    | KW_DELETE IDENT                                                        # StmtDeleteArr
    | KW_PRINT printArgs? printRedir?                                        # StmtPrint
    | KW_PRINTF printArgs printRedir?                                        # StmtPrintf
    | expr                                                                   # StmtExpr
    | /* empty */                                                            # StmtEmpty
    ;

forSimpleInit : expr ;
forSimpleStep : expr ;

// Print's argument list uses a `printExpr` chain that excludes `>`
// (and `|`) as top-level binary operators — POSIX says these always
// introduce a redirection when they appear at print/printf statement
// level outside parentheses. To use `>` as comparison or `|` for
// anything else in a print arg, parenthesize: `print (a > b)`.
printArgs
    : printExpr ( COMMA nl printExpr )*
    | LPAREN expr ( COMMA nl expr )* RPAREN     // POSIX permits parenthesizing the whole arglist
    ;

// Redirection tail. `>` truncates / overwrites, `>>` appends, `|`
// pipes to a command. The target is a `printExpr` so a `>` inside it
// would re-trigger the same exclusion — chained redirection is not a
// thing in awk.
printRedir
    : GT      printExpr     # PrintRedirFile
    | APPEND  printExpr     # PrintRedirAppend
    | PIPE    printExpr     # PrintRedirPipe
    ;

// -- Expressions ------------------------------------------------------------

exprList : expr ( COMMA nl expr )* ;

// `expr | getline [var]` is the command-pipe getline form. We layer it
// on top of `assignExpr` so the pipe binds with the lowest precedence
// and the left side can be any normal expression. Confined to `expr`
// (not `printExpr`) so it doesn't collide with print's `| "cmd"`
// redirection, which is handled at the statement level.
expr
    : assignExpr PIPE KW_GETLINE IDENT?           # ExprCmdGetline
    | assignExpr                                   # ExprAssign
    ;

assignExpr
    : ternaryExpr ( assignOp nl assignExpr )?
    ;

assignOp : ASSIGN | PLUSEQ | MINUSEQ | MULTEQ | DIVEQ | MODEQ | POWEQ ;

ternaryExpr
    : logOr ( QUESTION nl ternaryExpr COLON nl ternaryExpr )?
    ;

logOr  : logAnd ( OROR nl logAnd )* ;
logAnd : inExpr ( ANDAND nl inExpr )* ;

inExpr
    : matchExpr ( KW_IN IDENT )?
    | LPAREN exprList RPAREN KW_IN IDENT          // (i, j) in a — multi-subscript membership
    ;

matchExpr : relExpr ( ( MATCH | NOMATCH ) relExpr )* ;

relExpr
    : concatExpr ( relOp concatExpr )?
    ;

relOp : LT | LE | EQEQ | NEQ | GT | GE ;

// -- Print-arg expression chain ---------------------------------------------
//
// Mirrors the regular expression chain except `>` is dropped from the
// comparison set (it's reserved for redirection at this position).
// `|` is a top-level token that doesn't appear inside any expression
// rule, so it falls out naturally as the redirection marker without
// further plumbing.
//
// Only the layers that touch `>` or `|` differ; the rest pass through.
printExpr : printAssignExpr ;

printAssignExpr
    : printTernaryExpr ( assignOp nl printAssignExpr )?
    ;

printTernaryExpr
    : printLogOr ( QUESTION nl printTernaryExpr COLON nl printTernaryExpr )?
    ;

printLogOr  : printLogAnd ( OROR nl printLogAnd )* ;
printLogAnd : printInExpr ( ANDAND nl printInExpr )* ;

printInExpr
    : printMatchExpr ( KW_IN IDENT )?
    | LPAREN exprList RPAREN KW_IN IDENT
    ;

printMatchExpr : printRelExpr ( ( MATCH | NOMATCH ) printRelExpr )* ;

printRelExpr
    : concatExpr ( printRelOp concatExpr )?
    ;

printRelOp : LT | LE | EQEQ | NEQ | GE ;

// String concatenation by juxtaposition. We collect a non-empty run of
// addExpr terms; the AST builder folds them with BinOp.Concat. The
// follow-set rules out keywords/operators that *can't* start a new
// concat term (`,`, `;`, newline, `)`, `]`, `}`, etc.) — ANTLR handles
// that for us via the grammar's natural follow-set, since none of those
// tokens can start an addExpr.
concatExpr : addExpr+ ;

addExpr : mulExpr ( ( PLUS | MINUS ) mulExpr )* ;
mulExpr : powExpr ( ( STAR | DIV | MOD ) powExpr )* ;
powExpr : unaryExpr ( ( POW ) nl powExpr )? ;

unaryExpr
    : NOT unaryExpr                              # UnaryNot
    | MINUS unaryExpr                            # UnaryNeg
    | PLUS unaryExpr                             # UnaryPlus
    | preIncDec                                  # UnaryPassthrough
    ;

preIncDec
    : INC preIncDec                              # PreIncrement
    | DEC preIncDec                              # PreDecrement
    | postfixExpr                                # PreIncPassthrough
    ;

// Postfix `++ --` binds tighter than unary minus / pre-inc, but looser
// than `$` — POSIX awk parses `$NF++` as `($NF)++`, not `$(NF++)`.
// So postfix wraps fieldExpr (which is closer to primary).
postfixExpr
    : fieldExpr postIncDecOp?
    ;

// `$expr` — right-associative. `$$1` is `$( $1 )`, perfectly legal awk.
fieldExpr
    : DOLLAR fieldExpr                           # FieldOf
    | primary                                    # FieldPassthrough
    ;

postIncDecOp : INC | DEC ;

primary
    : NUMBER                                     # PrimNumber
    | STRING                                     # PrimString
    | REGEX                                      # PrimRegex
    | LPAREN expr RPAREN                         # PrimParen
    | IDENT LPAREN nl callArgs? nl RPAREN      # PrimFuncCall
    | IDENT LBRACK exprList RBRACK               # PrimArrayRef
    | IDENT                                      # PrimVar
    | KW_GETLINE IDENT? getlineSource?           # PrimGetline
    ;

callArgs : expr ( COMMA nl expr )* ;

// `getline < file` — the bare and `getline var` forms emerge naturally
// from `KW_GETLINE getlineSource?` because the IDENT case is absorbed by
// the no-source branch and treated as a regular primary at AST build
// time (we look for a trailing IDENT inside the getline expression).
// Slice 2.
getlineSource
    : LT primary                                  # GetlineFromFile
    ;
