// Parser for Makefile.
//
// Strategy: ANTLR produces a flat sequence of top-level "lines": each
// line is one of {assignment, rule-header, directive-line,
// conditional-line, define/endef line, recipe-line, blank}. The Kotlin
// AST builder walks this flat list to attach recipe lines to their rule
// header and pair conditional openers/closers.
//
// This avoids fighting ANTLR over make's offside-rules. The grammar is
// only responsible for shape recognition per line; semantics (which
// recipes belong to which rule, which `endif` closes which `if`) are
// trivial in Kotlin.
parser grammar MakefileParser;

options { tokenVocab = MakefileLexer; }

program : line* EOF ;

line
    : RECIPE_LINE NEWLINE?                # RecipeLineForm
    | NEWLINE                              # BlankLine
    | logicalLine NEWLINE?                 # LogicalLineForm
    ;

logicalLine
    : directive                            # LineDirective
    | conditionalOpener                    # LineCondOpener
    | KW_ELSE                              # LineElse
    | KW_ENDIF                             # LineEndif
    | KW_DEFINE words?                     # LineDefine
    | KW_ENDEF                             # LineEndef
    | assignOrRuleOrText                   # LineAssignRuleText
    ;

directive
    : ( KW_INCLUDE | KW_DASH_INCLUDE | KW_SINCLUDE ) words?              # IncludeDirective
    | KW_EXPORT words assignOp words?                                     # ExportAssignDirective
    | KW_EXPORT words?                                                    # ExportDirective
    | KW_UNEXPORT words                                                   # UnexportDirective
    | KW_OVERRIDE assignOrRuleOrText                                      # OverrideDirective
    ;

conditionalOpener
    : ( KW_IFEQ | KW_IFNEQ | KW_IFDEF | KW_IFNDEF ) words?
    ;

assignOrRuleOrText
    : words? assignOp words?                                      # FormAssign
    | words COLON words? ( SEMI words? )?                         # FormRule
    | words ( SEMI words? )?                                      # FormBareWords
    ;

assignOp
    : ASSIGN_EQ
    | ASSIGN_IMM
    | ASSIGN_DOUBLE
    | ASSIGN_TRIPLE
    | ASSIGN_DEFAULT
    | ASSIGN_APPEND
    | ASSIGN_SHELL
    ;

words : WORD+ ;
