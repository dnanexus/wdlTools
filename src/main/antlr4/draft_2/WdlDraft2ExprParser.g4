parser grammar WdlDraft2ExprParser;

options { tokenVocab=WdlDraft2Lexer; }

number
	: IntLiteral
	| FloatLiteral
	;

string_part
  : StringPart*
  ;

string
  : DQUOTE string_part DQUOTE
  | SQUOTE string_part SQUOTE
  ;

primitive_literal
	: BoolLiteral
	| number
	| string
	| Identifier
	;

expr
	: expr_infix
	;

expr_infix
	: expr_infix0 #infix0
	;

expr_infix0
	: expr_infix0 OR expr_infix1 #lor
	| expr_infix1 #infix1
	;

expr_infix1
	: expr_infix1 AND expr_infix2 #land
	| expr_infix2 #infix2
	;

expr_infix2
	: expr_infix2 EQUALITY expr_infix3 #eqeq
	| expr_infix2 NOTEQUAL expr_infix3 #neq
	| expr_infix2 LTE expr_infix3 #lte
	| expr_infix2 GTE expr_infix3 #gte
	| expr_infix2 LT expr_infix3 #lt
	| expr_infix2 GT expr_infix3 #gt
	| expr_infix3 #infix3
	;

expr_infix3
	: expr_infix3 PLUS expr_infix4 #add
	| expr_infix3 MINUS expr_infix4 #sub
	| expr_infix4 #infix4
	;

expr_infix4
	: expr_infix4 STAR expr_infix5 #mul
	| expr_infix4 DIVIDE expr_infix5 #divide
	| expr_infix4 MOD expr_infix5 #mod
	| expr_infix5 #infix5
	;

expr_infix5
	: expr_core
	;

expr_core
	: LPAREN expr RPAREN #expression_group
	| primitive_literal #primitives
	| LBRACK (expr (COMMA expr)*)* RBRACK #array_literal
	| LPAREN expr COMMA expr RPAREN #pair_literal
	| LBRACE (expr COLON expr (COMMA expr COLON expr)*)* RBRACE #map_literal
	| OBJECT_LITERAL LBRACE (Identifier COLON expr (COMMA Identifier COLON expr)*)* RBRACE #object_literal
	| NOT expr #negate
	| (PLUS | MINUS) expr #unirarysigned
	| expr_core LBRACK expr RBRACK #at
	| IF expr THEN expr ELSE expr #ifthenelse
	| Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
	| Identifier #left_name
	| expr_core DOT Identifier #get_name
	;

document
	: expr EOF
	;