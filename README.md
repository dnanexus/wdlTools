# WDL Scala Tools

Tools for parsing and type-checking WDL programs written in the [Scala programming language](https://www.scala-lang.org).

## Abstract Syntax Tree (AST)

The first goal is to
create an Abstract Syntax Tree (AST) from an
[Antlr4 grammar]((https://github.com/patmagee/wdl/tree/grammar-remake))
developed by Patrick Magee.


## Building

The java code was generated from the `.g4` sources with ANTLR4 in this way:

```
java -jar ~/antlr-4.8-complete.jar -o java -visitor -package org.openwdl.wdl.parser WdlParser.g4 WdlLexer.g4
```

The scala code was then compiled with:
```
sbt compile
```

to run the tests do:
```
sbt test
```
