ROOT_DIR:=$(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))

all : download_antlr_jar draft2 v10

download_antlr_jar:
	(if [ ! -e ${ROOT_DIR}/antlr-4.8-complete.jar ]; then \
		wget https://www.antlr.org/download/antlr-4.8-complete.jar; \
	fi)

draft2 :
	(cd ${ROOT_DIR}/src/main/antlr4/draft_2;  java -jar ${ROOT_DIR}/antlr-4.8-complete.jar -o ${ROOT_DIR}/src/main/java -visitor -package org.openwdl.wdl.parser.draft_2 Draft2WdlParser.g4 Draft2WdlLexer.g4)

v10 :
	(cd ${ROOT_DIR}/src/main/antlr4/v1_0; java -jar ${ROOT_DIR}/antlr-4.8-complete.jar -o ${ROOT_DIR}/src/main/java -visitor -package org.openwdl.wdl.parser.v1_0 V10WdlParser.g4 V10WdlLexer.g4)


clean :
	rm -rf src/main/java
