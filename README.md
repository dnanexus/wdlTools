# WDL Scala Tools

Tools for parsing and type-checking WDL programs written in the [Scala programming language](https://www.scala-lang.org).

## Abstract Syntax Tree (AST)

The `wdlTools.syntax` package implements an abstract syntax tree (AST) for WDL. It uses [Antlr4 grammar]((https://github.com/patmagee/wdl/tree/grammar-remake)) developed by Patrick Magee. The class definitions reside in `src/main/scala/wdlTools/syntax/AbstractSyntax.scala`. In order to parse a document you need to do:

```scala
    val pa = new ParseAll()
    val doc : AbstractSyntax = pa.apply(WDL_SOURCE_CODE)
```

## Typechecker

The typechecker is currently in development.


## Building

The java code for the parser was generated with the [ANTRL4](https://www.antlr.org) tool.

1. Download a jar file with the [instructions](https://www.antlr.org/download.html)
2. `cd GIT_REPO`
3. Assuming you downloaded the jar file to `$HOME/antlr-4.8-complete.jar` generated java code from the grammar in this way:

```
cd GIT_REPO/src/main/antrl4
java -jar ~/antlr-4.8-complete.jar -o GIT_REPO_ROOT/src/main/java -visitor -package org.openwdl.wdl.parser WdlParser.g4 WdlLexer.g4
mv src/main/java GIT_REPO/src/main/
cd GIT_REPO
```

4. Build the scala code:
```
sbt compile
```

5. Run the tests:
```
sbt test
```
