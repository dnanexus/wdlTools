parser grammar Draft2WdlTypeParser;

options { tokenVocab=Draft2WdlLexer; }

map_type
	: MAP LBRACK wdl_type COMMA wdl_type RBRACK
	;

array_type
	: ARRAY LBRACK wdl_type RBRACK PLUS?
	;

pair_type
	: PAIR LBRACK wdl_type COMMA wdl_type RBRACK
	;

type_base
	: array_type
	| map_type
	| pair_type
	| (STRING | FILE | BOOLEAN | OBJECT | INT | FLOAT)
	;

wdl_type
  : (type_base OPTIONAL | type_base)
  ;

document
	: wdl_type EOF
	;