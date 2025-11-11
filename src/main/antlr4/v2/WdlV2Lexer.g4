lexer grammar WdlV2Lexer;

channels { COMMENTS }

// Comments
LINE_COMMENT: '#' ~[\r\n]* -> channel(COMMENTS);

// Keywords
VERSION: 'version' -> pushMode(Version);
IMPORT: 'import';
WORKFLOW: 'workflow';
TASK: 'task';
STRUCT: 'struct';
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
PARAMETERMETA: 'parameter_meta' -> mode(Meta);
META: 'meta' -> mode(Meta);
HINTS: 'hints' -> mode(Meta);
RUNTIME: 'runtime';
BOOLEAN: 'Boolean';
INT: 'Int';
FLOAT: 'Float';
STRING: 'String';
FILE: 'File';
DIRECTORY: 'Directory';
ARRAY: 'Array';
MAP: 'Map';
OBJECTLITERAL: 'object';
PAIR: 'Pair';
AFTER: 'after';
COMMAND: 'command'-> mode(Command);

// Primitive Literals
NONELITERAL: 'None';
IntLiteral: Digits;
FloatLiteral: FloatFragment;
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
COMMA: ',';
SEMI: ';';
DOT: '.';
NOT: '!';
TILDE: '~';
DIVIDE: '/';
MOD: '%';
SQUOTE: '\'' -> pushMode(SquoteInterpolatedString);
DQUOTE: '"' -> pushMode(DquoteInterpolatedString);
MULTILINE: '<<<' -> pushMode(MultilineInterpolatedString);

WHITESPACE: [ \t\r\n]+ -> channel(HIDDEN);

Identifier: CompleteIdentifier;

mode SquoteInterpolatedString;

EscStringPart: StringEscapeSequence;
SQuoteTildeString: '~' -> type(StringPart);
SQuoteCurlyString: '{' -> type(StringPart);
SQuoteCommandStart: '~{' -> pushMode(DEFAULT_MODE), type(StringCommandStart);
EndSquote: '\'' ->  popMode, type(SQUOTE);
StringPart: ~[~{\r\n'\\]+;

mode DquoteInterpolatedString;

DQuoteEscapedChar: StringEscapeSequence -> type(EscStringPart);
DQuoteTildeString: '~' -> type(StringPart);
DQUoteCurlyString: '{' -> type(StringPart);
DQuoteCommandStart: '~{' -> pushMode(DEFAULT_MODE), type(StringCommandStart);
EndDQuote: '"' ->  popMode, type(DQUOTE);
DQuoteStringPart: ~[~{\r\n"\\]+ -> type(StringPart);

mode MultilineInterpolatedString;

MultilineEscapedChar: MultilineEscapeSequence -> type(EscStringPart);
MultilineTildeString: '~' -> type(StringPart);
MultilineCurlyString: '{' -> type(StringPart);
MultilineCommandStart: '~{' -> pushMode(DEFAULT_MODE), type(StringCommandStart);
MultilineEscapedEnd: '\\>>>' -> type(StringPart);
EndMultiline: '>>>' -> popMode, type(MULTILINE);
MultilineEscapedArrow: ( '>' | '>>' | '>>>>' '>'*) -> type(StringPart);
MultilineStringPart: ~[~{>\\]+ -> type(StringPart);

mode Command;

BeginWhitespace: [ \t\r\n]+ -> channel(HIDDEN);
BeginHereDoc: '<<<' -> mode(HereDocCommand);

mode HereDocCommand;

HereDocTildeString: '~' -> type(CommandStringPart);
HereDocCurlyString: '{' -> type(CommandStringPart);
HereDocCurlyStringCommand: '~{' -> pushMode(DEFAULT_MODE), type(StringCommandStart);
HereDocEscapedEnd: '\\>>>' -> type(CommandStringPart);
EndHereDocCommand: '>>>' -> mode(DEFAULT_MODE), type(EndCommand);
HereDocEscape: ( '>' | '>>' | '>>>>' '>'*) -> type(CommandStringPart);
HereDocStringPart: ~[~{>]+ -> type(CommandStringPart);

mode Version;

VersionWhitespace: [ \t]+ -> channel(HIDDEN);
ReleaseVersion: [a-zA-Z0-9.-]+ -> popMode;

mode Meta;

BeginMeta: '{' -> pushMode(MetaBody);
MetaWhitespace: [ \t\r\n]+ -> channel(HIDDEN);

mode MetaBody;

MetaBodyComment: '#' ~[\r\n]* -> channel(COMMENTS);
MetaIdentifier: Identifier;
MetaColon: ':' -> pushMode(MetaValue);
EndMeta: '}' -> popMode, mode(DEFAULT_MODE);
MetaBodyWhitespace: [ \t\r\n]+ -> channel(HIDDEN);

mode MetaValue;

MetaValueComment: '#' ~[\r\n]* -> channel(COMMENTS);
MetaBool: BoolLiteral -> popMode;
MetaInt: IntLiteral -> popMode;
MetaFloat: FloatLiteral -> popMode;
MetaNull: 'null' -> popMode;
MetaSquote: '\'' -> pushMode(MetaSquoteString);
MetaDquote: '"' -> pushMode(MetaDquoteString);
MetaEmptyObject: '{' [ \t\r\n]* '}' -> popMode;
MetaEmptyArray: '[' [ \t\r\n]* ']' -> popMode;
MetaLbrack: '[' -> pushMode(MetaArray), pushMode(MetaValue);
MetaLbrace: '{' -> pushMode(MetaObject);
MetaValueWhitespace: [ \t\r\n]+ -> channel(HIDDEN);

mode MetaSquoteString;

MetaEscStringPart: MetaEscapeSequence;
MetaEndSquote: '\'' ->  popMode, type(MetaSquote), popMode;
MetaStringPart: ~[\r\n'\\]+;

mode MetaDquoteString;

MetaDquoteEscapedChar: EscapeSequence -> type(MetaEscStringPart);
MetaEndDquote: '"' ->  popMode, type(MetaDquote), popMode;
MetaDquoteStringPart: ~[\r\n"\\]+ -> type(MetaStringPart);

mode MetaArray;

MetaArrayComment: '#' ~[\r\n]* -> channel(COMMENTS);
MetaArrayCommaRbrack: ',' [ \t\r\n]* ']' -> popMode, popMode;
MetaArrayComma: ',' -> pushMode(MetaValue);
MetaRbrack: ']' -> popMode, popMode;
MetaArrayWhitespace: [ \t\r\n]+ -> channel(HIDDEN);

mode MetaObject;

MetaObjectComment: '#' ~[\r\n]* -> channel(COMMENTS);
MetaObjectIdentifier: Identifier;
MetaObjectColon: ':' -> pushMode(MetaValue);
MetaObjectCommaRbrace: ',' [ \t\r\n]* '}' -> popMode, popMode;
MetaObjectComma: ',';
MetaRbrace: '}' -> popMode, popMode;
MetaObjectWhitespace: [ \t\r\n]+ -> channel(HIDDEN);

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

fragment SpecialEscapeSequence
	| ESC OctEsc
	| ESC HexEsc
	| ESC UnicodeEsc
	| ESC UnicodeEsc2
	;

fragment StringEscapeSequence
	: ESC [~tn"'\\]
	| SpecialEscapeSequence
	;

fragment MultilineEscapeSequence
	: ESC [~>\\]
	| SpecialEscapeSequence
	;

fragment MetaEscapeSequence
	: ESC [tn"'\\]
	| SpecialEscapeSequence
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
