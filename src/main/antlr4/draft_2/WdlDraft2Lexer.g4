lexer grammar WdlDraft2Lexer;

channels { COMMENTS }

// Comments
LINE_COMMENT: '#' ~[\r\n]* -> channel(COMMENTS);

// Keywords
IMPORT: 'import';
WORKFLOW: 'workflow';
TASK: 'task';
SCATTER: 'scatter';
CALL: 'call';
IF: 'if';
THEN: 'then';
ELSE: 'else';
ALIAS: 'alias';
AS: 'as';
In: 'in';
INPUT: 'input';
OUTPUT: 'output';
PARAMETERMETA: 'parameter_meta';
META: 'meta';
COMMAND: 'command' -> mode(Command);
RUNTIME: 'runtime';
BOOLEAN: 'Boolean';
INT: 'Int';
FLOAT: 'Float';
STRING: 'String';
FILE: 'File';
ARRAY: 'Array';
MAP: 'Map';
PAIR: 'Pair';
OBJECT: 'Object';
OBJECTLITERAL: 'object';

SEPEQUAL: 'sep=';
DEFAULTEQUAL: 'default=';

// Primitive Literals
IntLiteral
	: Digits
	;
FloatLiteral
	: FloatFragment
	;
BoolLiteral
	: 'true'
	| 'false'
	;

// Symbols
LPAREN: '(';
RPAREN: ')';
LBRACE: '{' -> pushMode(DEFAULT_MODE);
RBRACE: '}' -> popMode;
LBRACK: '[';
RBRACK: ']';
ESC: '\\';
COLON: ':';
LT: '<';
GT: '>';
GTE: '>=';
LTE: '<=';
EQUALITY: '==';
NOTEQUAL: '!=';
EQUAL: '=';
AND: '&&';
OR: '||';
OPTIONAL: '?';
STAR: '*';
PLUS: '+';
MINUS: '-';
DOLLAR: '$';
COMMA: ',';
SEMI: ';';
DOT: '.';
NOT: '!';
DIVIDE: '/';
MOD: '%';
SQUOTE: '\'' -> pushMode(SquoteInterpolatedString);
DQUOTE: '"' -> pushMode(DquoteInterpolatedString);

WHITESPACE: [ \t\r\n]+ -> channel(HIDDEN);

Identifier: CompleteIdentifier;

mode SquoteInterpolatedString;

EscStringPart: EscapeSequence;
SQuoteDollarString: '$'  -> type(StringPart);
SQuoteCurlyString: '{' -> type(StringPart);
SQuoteCommandStart: ('${') -> pushMode(DEFAULT_MODE) , type(StringCommandStart);
EndSquote: '\'' ->  popMode, type(SQUOTE);
StringPart: ~[${\r\n'\\]+;

mode DquoteInterpolatedString;

DQuoteEscapedChar: EscapeSequence -> type(EscStringPart);
DQuoteDollarString: '$' -> type(StringPart);
DQUoteCurlString: '{' -> type(StringPart);
DQuoteCommandStart: ('${') -> pushMode(DEFAULT_MODE), type(StringCommandStart);
EndDQuote: '"' ->  popMode, type(DQUOTE);
DQuoteStringPart: ~[${\r\n"\\]+ -> type(StringPart);

mode Command;

BeginWhitespace: [ \t\r\n]+ -> channel(HIDDEN);
BeginHereDoc: '<<<' -> mode(HereDocCommand);
BeginLBrace: '{' -> mode(CurlyCommand);

mode CurlyCommand;

CommandDollarString: '$' -> type(CommandStringPart);
CommandCurlyString: '{' -> type(CommandStringPart);
StringCommandStart:  ('${') -> pushMode(DEFAULT_MODE);
EndCommand: '}' -> mode(DEFAULT_MODE);
CommandStringPart: ~[${}]+;

mode HereDocCommand;

HereDocDollarString: '$' -> type(CommandStringPart);
HereDocCurlyString: '{' -> type(CommandStringPart);
HereDocCurlyStringCommand: ('${') -> pushMode(DEFAULT_MODE), type(StringCommandStart);
HereDocEscapedEnd: '\\>>>' -> type(CommandStringPart);
EndHereDocCommand: '>>>' -> mode(DEFAULT_MODE), type(EndCommand);
HereDocEscape: ( '>' | '>>' | '>>>>' '>'*) -> type(CommandStringPart);
HereDocStringPart: ~[${>]+ -> type(CommandStringPart);

// Fragments

fragment CompleteIdentifier
	: IdentifierStart IdentifierFollow*
	;

fragment IdentifierStart
	: [a-zA-Z]
	;

fragment IdentifierFollow
	: [a-zA-Z0-9_]+
	;

fragment OctDigit
  : [0-7]
  ;

fragment HexDigit
	: [0-9a-fA-F]
	;

fragment OctEsc
  : OctDigit OctDigit OctDigit
  ;

fragment HexEsc
  : 'x' HexDigit HexDigit
  ;

fragment UnicodeEsc
	: 'u' HexDigit HexDigit HexDigit HexDigit
	;

fragment UnicodeEsc2
	: 'U' HexDigit HexDigit HexDigit HexDigit HexDigit HexDigit HexDigit HexDigit
	;

fragment EscapeSequence
	: ESC [tn"'\\]
	| ESC OctEsc
	| ESC HexEsc
	| ESC UnicodeEsc
	| ESC UnicodeEsc2
	;

fragment Digit
	: [0-9]
	;

fragment Digits
	: Digit+
	;

fragment Decimals
	: Digits '.' Digits? | '.' Digits
	;

fragment SignedDigits
	: ('+' | '-' ) Digits
	;

fragment FloatFragment
	: Digits EXP?
	| Decimals EXP?
	;

fragment EXP
	: ('e' | 'E') SignedDigits
	;