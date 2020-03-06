all : gen move

gen:
	(cd src/main/antlr4; java -jar ~/antlr-4.8-complete.jar -o java -visitor -package org.openwdl.wdl.parser WdlParser.g4 WdlLexer.g4)

move:
	pwd
	rm -rf src/main/java
	mv src/main/antlr4/java src/main/
