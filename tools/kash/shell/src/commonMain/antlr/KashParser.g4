// Parser-only grammar for kash shell scripts.
//
// Tokens are produced by the hand-written com.accucodeai.kash.parser.Lexer
// and fed in via KashTokenSourceAdapter. The lexer handles the genuinely
// context-sensitive parts of bash (heredocs, $(...) pre-scan, [[ ]]/(( ))
// mode tracking, command-position keyword emission). This grammar only
// describes how those tokens compose into statements and compound commands.
parser grammar KashParser;

@parser::members {
 private val UNARY_COND_OPS = setOf(
 "-a", "-b", "-c", "-d", "-e", "-f", "-g", "-h", "-k", "-L",
 "-n", "-o", "-p", "-r", "-s", "-S", "-t", "-u", "-w", "-x",
 "-z", "-G", "-N", "-O", "-v", "-R",
 )
 private val BINARY_COND_OPS = setOf(
 "=", "==", "!=", "=~",
 "-eq", "-ne", "-lt", "-le", "-gt", "-ge",
 "-ef", "-nt", "-ot",
 )
 private fun isUnaryCondOp(text: String?): Boolean = text != null && text in UNARY_COND_OPS
 private fun isBinaryCondOp(text: String?): Boolean = text != null && text in BINARY_COND_OPS

 // Compound-command starter text — used to gate `coproc NAME compound` vs
 // `coproc cmd args`. After `coproc NAME` the lexer has atCommandStart=false,
 // so `{` `(` `if` `for` `while` `until` `case` all arrive as WORDs. The
 // matching compound rules already accept WORD-text fallbacks, but ANTLR's
 // ALL(*) prediction doesn't walk through enough nested rules to consult
 // those predicates in deep contexts — so the top-level coprocTail
 // alternative checks the text directly via this helper.
 private fun isCoprocCompoundStart(text: String?): Boolean =
 text != null && (
 text == "{" || text == "(" ||
 text == "if" || text == "for" || text == "select" ||
 text == "while" || text == "until" || text == "case"
 )

 // Per-rule LL prediction toggle for [compoundList]. The IDEA profiler
 // (May 2026) showed `hasSLLConflictTerminatingPrediction` consuming
 // 96% of parse time at the `KashParser.compoundList` decision when
 // input nests deeply (200 nested `for` loops). SLL almost-decides on
 // this rule and grinds; raw LL prediction handles the same input
 // in milliseconds. The depth counter keeps nested `compoundList`
 // calls in LL mode and only restores SLL when the outermost frame
 // exits — without it, an inner `compoundList`'s exit would flip the
 // outer back to SLL mid-parse.
 private var compoundListDepth: Int = 0

 private fun enterCompoundListLL() {
 if (compoundListDepth == 0) {
 interpreter.predictionMode = org.antlr.v4.kotlinruntime.atn.PredictionMode.LL
 }
 compoundListDepth++
 }

 private fun exitCompoundListLL() {
 compoundListDepth--
 if (compoundListDepth == 0) {
 interpreter.predictionMode = org.antlr.v4.kotlinruntime.atn.PredictionMode.SLL
 }
 }
}

tokens {
 // Word-shaped tokens (carry payload on the source Token).
 WORD,
 ASSIGN,
 ARRAY_ASSIGN,
 INDEXED_ASSIGN,
 HEREDOC_BODY,
 ARITH_CMD,

 // Line terminator (statement separator).
 NL,

 // Reserved words. Emitted only at command-position by the lexer.
 KW_IF, KW_THEN, KW_ELIF, KW_ELSE, KW_FI,
 KW_FOR, KW_SELECT, KW_IN, KW_DO, KW_DONE,
 KW_WHILE, KW_UNTIL,
 KW_CASE, KW_ESAC,
 KW_FUNCTION,
 KW_LBRACE, KW_RBRACE,
 KW_BANG,
 KW_DLBRACK, KW_DRBRACK,
 KW_COPROC,
 KW_TIME,

 // Control operators.
 OP_PIPE, OP_PIPE_AMP, OP_AND_AND, OP_OR_OR, OP_AMP, OP_SEMI,
 OP_DSEMI, OP_DSEMI_AMP, OP_DSEMI_SEMI,
 OP_LPAREN, OP_RPAREN,

 // Redirections (fd prefix preserved on source Token).
 REDIR_LT, REDIR_GT, REDIR_DGT, REDIR_GT_PIPE, REDIR_LT_GT,
 REDIR_GT_AMP, REDIR_LT_AMP,
 REDIR_AMP_GT, REDIR_AMP_DGT,
 REDIR_HSTRING, REDIR_HEREDOC, REDIR_HEREDOC_STRIP
}

// -------- Entry point --------

script
@init { enterCompoundListLL() }
@after { exitCompoundListLL() }
 : sep* (statement (sep+ statement)*)? sep* EOF
 ;

// A statement is a chain of pipelines joined by && / ||. POSIX `&` is NOT
// a statement-internal trailer — it's a list separator equivalent to `;`
// that additionally backgrounds the preceding statement (see `sep` below
// and the AstBuilder, which pairs each statement with the sep that follows
// it to decide if it should run async).
statement
 : pipeline (connector NL* pipeline)*
 ;

connector
 : OP_AND_AND
 | OP_OR_OR
 ;

// Pipeline prefixes (`time`, `!`) compose recursively: each prefix wraps
// the inner pipeline-command, ORing its flag onto the same inner command.
// So nested `time time …` yields one timing-wrapped command, not two. We
// mirror that recursion here; AstBuilder collapses the chain into a single
// Pipeline with one `negated` parity bit and one `timed` value.
pipeline
 : pipelineCmd
 ;

pipelineCmd
 : KW_TIME timeOpt? pipelineCmd? # timedPipelineCmd
 | KW_BANG pipelineCmd? # bangPipelineCmd
 | command (pipeSep NL* command)* # untimedPipelineCmd
 ;

// `|` and `|&` are both pipe separators; the only difference is that `|&`
// also routes the LHS's stderr to the RHS (equivalent to `2>&1 |`).
pipeSep
 : OP_PIPE
 | OP_PIPE_AMP
 ;

// `-p` and/or `--` following `time`. Predicated so only literal `-p` /
// `--` get absorbed — otherwise `time echo a` would swallow `echo` as a
// timeOpt and leave no command.
timeOpt
 : {_input.LT(1)?.text == "-p"}? WORD
 ({_input.LT(1)?.text == "--"}? WORD)?
 | {_input.LT(1)?.text == "--"}? WORD
 ;

// -------- Commands --------

command
 : ifCmd
 | forCmd
 | selectCmd
 | whileCmd
 | untilCmd
 | caseCmd
 | groupCmd
 | subshellCmd
 | condCmd
 | arithCmd
 | functionFuncDef
 | nameFuncDef
 | coprocCmd
 | simpleCommand
 ;

// Bash coproc — runs body async with two pipes auto-wired to ${NAME}[0]/[1]
// and the child pid in ${NAME}_PID. NAME is optional; defaults to COPROC.
// Body is either a compound command (typically `{ ... }`) or a bare
// simpleCommand (`coproc xcase -n -u`).
//
// Disambiguation: `coproc NAME { ... }` needs the NAME word lexer-emitted as
// WORD before the body. `coproc cmd args` is just coproc + simpleCommand.
// The `coprocName` alternative requires a following compound — without one,
// the NAME would have been the command itself.
// Left-factored on KW_COPROC so ALL(*) can disambiguate the three tail
// shapes by looking ahead through each tail's ATN. The compound rules
// (groupCmd etc.) start with `kwLbrace` / `OP_LPAREN` / a keyword token —
// each of those is distinct from `simpleCommand`'s `commandPart+` (which
// starts with WORD/ASSIGN/redirection), so prediction reaches a unique
// alt without backtracking. The WORD-prefix `coproc NAME compound` and
// the bare `coproc cmd args` both start with WORD; the disambiguating
// `kwLbrace`'s text-predicate in the existing rule (`{...== "{"}? WORD`)
// is hoisted into prediction since it sits on the left edge of `kwLbrace`'s
// alt — making the choice clean.
//
// Order matters: `simpleCommand` last per [antlr4/predicates.md]: "If more
// than one viable alternative remains, the parser chooses the alternative
// specified first in the decision."
coprocCmd
 : KW_COPROC coprocTail
 ;

// Top-level predicates on each alt's left edge so ALL(*) consults them
// during prediction (per antlr4/predicates.md: "only those appearing on the
// left edge of alternatives can affect prediction"). Without this, ALL(*)
// would not walk through coprocCompound → groupCmd → kwLbrace to consult
// the existing WORD-text predicate, and would pick the WORD-prefix alt for
// `coproc cmd arg arg` then choke at the second arg.
coprocTail
 : {_input.LT(2)?.let { isCoprocCompoundStart(it.text) } == true}?
 coprocName coprocCompound # coprocNamedCompound
 | {isCoprocCompoundStart(_input.LT(1)?.text)}?
 coprocCompound # coprocAnonymousCompound
 | simpleCommand # coprocSimple
 ;

// A coproc's body accepts the full compound-command set:
// for/case/while/until/select/if/subshell/group/arith/cond. Cover that
// breadth so `coproc x (( 1 ))` and `coproc x [[ -f a ]]` parse.
coprocCompound
 : groupCmd | subshellCmd | ifCmd | forCmd | whileCmd | untilCmd
 | caseCmd | selectCmd | condCmd | arithCmd
 ;

coprocName
 : WORD
 ;

// -------- Compound commands --------

// Use sub-rules for elif/else clauses so AstBuilder can iterate them as
// list/optional contexts without counting individual keyword tokens.
ifCmd
 : KW_IF sep* compoundList sep* kwThen sep* compoundList sep*
 elifClause*
 elseClause?
 kwFi redirection*
 ;

elifClause : kwElif sep* compoundList sep* kwThen sep* compoundList sep* ;
elseClause : kwElse sep* compoundList sep* ;

// Bash for-loops come in three shapes:
// for ((init; cond; update)); do ... done -- arithForHeader
// for x in a b c; do ... done -- forInClause present
// for x; do ... done -- bare; iterates over "$@"
// forInClause doesn't consume the trailing separator; the outer sep* does,
// so the `for x; do` (no `in`) case also works.
forCmd
 : KW_FOR arithForHeader sep* body redirection*
 | KW_FOR forVar forInClause? sep* body redirection*
 ;

arithForHeader : ARITH_CMD ;

// `select var [in list]; do ...; done` — same shape as `for`, different keyword.
selectCmd
 : KW_SELECT forVar forInClause? sep* body redirection*
 ;

// Bash allows reserved words as the iteration variable when an `in` follows
// (e.g. `for for in for; do ...; done`). The lexer emits the keyword token
// here, so accept either WORD or any reserved word.
forVar
 : WORD
 | anyKeyword
 ;

forInClause
 : NL* kwIn forWord*
 ;

forWord : WORD ;

whileCmd
 : KW_WHILE sep* compoundList sep* kwDo sep* compoundList sep* kwDone redirection*
 ;

untilCmd
 : KW_UNTIL sep* compoundList sep* kwDo sep* compoundList sep* kwDone redirection*
 ;

caseCmd
 : KW_CASE caseWord sep* kwIn sep* caseItem* kwEsac redirection*
 ;

caseWord
 : WORD
 | anyKeyword
 ;

caseItem
 : OP_LPAREN? casePattern (OP_PIPE casePattern)* OP_RPAREN sep*
 caseItemBody
 ;

casePattern
 : WORD
 | anyKeyword
 ;

caseItemBody
 : statement (sep+ statement)* sep* caseTerminator sep*
 | sep* caseTerminator sep*
 | statement (sep+ statement)* sep*
 | // empty (final item with no terminator and no body)
 ;

caseTerminator
 : OP_DSEMI
 | OP_DSEMI_AMP
 | OP_DSEMI_SEMI
 ;

groupCmd
 : kwLbrace sep* compoundList sep* kwRbrace redirection*
 ;

subshellCmd
 : OP_LPAREN sep* compoundList sep* OP_RPAREN redirection*
 ;

// -------- [[ ... ]] conditional --------

// The lexer keeps `atCommandStart` false after the first cond operand, so the
// closing `]]` arrives as a plain WORD token rather than KW_DRBRACK. Accept
// both forms — the legacy parser's `peekIsWord("]]")` does the same.
condCmd
 : KW_DLBRACK condExpr drbrack redirection*
 ;

drbrack
 : KW_DRBRACK
 | {_input.LT(1)?.text == "]]"}? WORD
 ;

// Helpers for reserved words that the lexer emits as plain WORD tokens when
// `atCommandStart` is false (typically the second time a keyword appears
// inside a compound construct — `in` after `for x`, `]]` after the cond
// operand, `done`/`fi`/`esac` after an unterminated expression, etc.).
// Each accepts either the keyword token or a WORD whose text matches.
kwIn : KW_IN | {_input.LT(1)?.text == "in"}? WORD ;
kwDo : KW_DO | {_input.LT(1)?.text == "do"}? WORD ;
kwDone : KW_DONE | {_input.LT(1)?.text == "done"}? WORD ;
kwThen : KW_THEN | {_input.LT(1)?.text == "then"}? WORD ;
kwElif : KW_ELIF | {_input.LT(1)?.text == "elif"}? WORD ;
kwElse : KW_ELSE | {_input.LT(1)?.text == "else"}? WORD ;
kwFi : KW_FI | {_input.LT(1)?.text == "fi"}? WORD ;
kwEsac : KW_ESAC | {_input.LT(1)?.text == "esac"}? WORD ;
kwLbrace : KW_LBRACE | {_input.LT(1)?.text == "{"}? WORD ;
kwRbrace : KW_RBRACE | {_input.LT(1)?.text == "}"}? WORD ;

condExpr : condOr ;

condOr : condAnd (OP_OR_OR condAnd)* ;
condAnd : condUnary (OP_AND_AND condUnary)* ;
condUnary
 : KW_BANG condUnary
 | condPrimary
 ;
// Disambiguation between unary/binary/lone is done by predicates that peek at
// the upcoming WORD's text. Lexer-level distinctions (< > vs WORD) handled in
// the grammar shape. The adapter sets a WORD token's text to its single literal
// content (empty string if the word is quoted/expanded), so quoted ops like
// `[[ a "=" b ]]` correctly fall through to condLone rather than condTriWord.
condPrimary
 : OP_LPAREN condExpr OP_RPAREN # condParen
 | {isUnaryCondOp(_input.LT(1)?.text)}? WORD WORD # condUnaryWord
 | {isBinaryCondOp(_input.LT(2)?.text)}? WORD WORD WORD # condTriWord
 | WORD (REDIR_LT | REDIR_GT) WORD # condStringCmp
 | WORD # condLone
 ;

// -------- (( arithmetic )) --------

arithCmd : ARITH_CMD redirection* ;

// -------- Function definitions --------

functionFuncDef
 : KW_FUNCTION fnName (OP_LPAREN OP_RPAREN)? sep* fnBody redirection*
 ;

nameFuncDef
 : WORD OP_LPAREN OP_RPAREN sep* fnBody redirection*
 ;

fnName
 : WORD
 | anyKeyword
 ;

// A function body is a single compound command plus optional
// redirections. Include `selectCmd`, `condCmd`, `arithCmd` so
// `f() (( i++ ))`, `f() [[ -f x ]]`, and `f() select v; do …; done` parse.
fnBody
 : groupCmd
 | subshellCmd
 | ifCmd
 | forCmd
 | whileCmd
 | untilCmd
 | caseCmd
 | selectCmd
 | condCmd
 | arithCmd
 ;

// -------- Simple command --------

simpleCommand
 : commandPart+
 ;

commandPart
 : ASSIGN
 | ARRAY_ASSIGN
 | INDEXED_ASSIGN
 | WORD
 | redirection
 ;

// -------- Compound list (body of compound commands) --------

// Surrounding `sep*` lives at each caller — not here — so that this
// rule's only decision is "another statement?". The follow set is the
// caller's tail (kwDone/kwThen/kwElse/kwElif/kwFi/kwRbrace/OP_RPAREN);
// no statement starts with any of those, so the decision is locally
// SLL-decidable. The @init/@after actions additionally flip prediction
// to LL for the entire compoundList subtree — SLL's
// `hasSLLConflictTerminatingPrediction` was 96% of parse cost on deeply
// nested compound commands; raw LL prediction handles the same input
// in milliseconds (see helpers above + plans/fancy-hopping-piglet.md).
compoundList
@init { enterCompoundListLL() }
@after { exitCompoundListLL() }
 : (statement (sep+ statement)*)?
 ;

// for/while/until body
body
 : kwDo sep* compoundList sep* kwDone
 | kwLbrace sep* compoundList sep* kwRbrace
 ;

// -------- Redirection --------

redirection
 : (REDIR_HEREDOC | REDIR_HEREDOC_STRIP) HEREDOC_BODY
 | redirOpFile WORD
 ;

redirOpFile
 : REDIR_LT
 | REDIR_GT
 | REDIR_DGT
 | REDIR_GT_PIPE
 | REDIR_LT_GT
 | REDIR_GT_AMP
 | REDIR_LT_AMP
 | REDIR_AMP_GT
 | REDIR_AMP_DGT
 | REDIR_HSTRING
 ;

// Reserved words that may appear as plain identifiers in non-command position
// (e.g. as case patterns, function names, case subjects).
anyKeyword
 : KW_IF | KW_THEN | KW_ELIF | KW_ELSE | KW_FI
 | KW_FOR | KW_SELECT | KW_IN | KW_DO | KW_DONE
 | KW_WHILE | KW_UNTIL
 | KW_CASE | KW_ESAC
 | KW_FUNCTION
 | KW_BANG
 | KW_TIME
 | KW_COPROC
 ;

// Statement separator. POSIX treats `&` and `;` as equivalent list
// separators — `cmd1 & cmd2` is two commands, the first backgrounded.
// The AstBuilder walks the parent's children and, for each statement,
// checks whether the immediately-following sep tokens include an OP_AMP.
sep
 : NL
 | OP_SEMI
 | OP_AMP
 ;
