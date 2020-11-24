# WDL Scala Tools

[Scala programming language](https://www.scala-lang.org) library for parsing [WDL](https://openwdl.org), and command-line tools for type-checking, code formatting, and more.

## Abstract Syntax Tree (AST)

The `wdlTools.syntax` package implements an [abstract syntax tree (AST)](src/main/scala/wdlTools/syntax/AbstractSyntax.scala) for WDL. It uses [Antlr4 grammar](https://github.com/patmagee/wdl/tree/grammar-remake) developed by Patrick Magee.

Currently, WDL draft-2 and 1.0, and development/2.0 are fully supported (with some [limitations](#limitations)).

```scala
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.Parsers
import dx.util.{FileNode, FileSourceResolver}
val parsers = Parsers(followImports = true)
val wdl: FileNode = FileSourceResolver.get.resolve("file:///path/to/my/wdl")
val doc: Document = parsers.parseDocument(wdl)
// print the source locations of all tasks in the document
doc.elements.foreach {
  case task: Task => println(s"Task ${task.name} is at ${task.loc}")
}
```

## Command line tools

The wdlTools JAR also provides various command line tools to accelerate and simplify the development of WDL workflows. You can get help by running:

```commandline
$ java -jar wdlTools.jar [command] --help
```

The following commands are currently available. They should be considered "alpha" quality - please report any bugs/enhancements using the [issue tracker](https://github.com/dnanexus-rnd/wdlTools/issues).

* [check](doc/Commands/Check.md): type-check a WDL file
* [docgen](doc/Commands/Docgen.md): generate documentation for WDL tasks/workflows
* [exec](doc/Commands/Exec.md): execute a WDL task (workflow execution is not yet supported)
* [format](doc/Commands/Format.md): reformat a WDL file
* [lint](doc/Commands/Lint.md): detect "lint" (i.e. incorrect style or potentially problematic code) in a WDL file
* [new](doc/Commands/New.md): generate a new WDL project
* [printTree](doc/Commands/PrintTree.md): print the Abstract Syntax Tree for a WDL document
* [readmes](doc/Commands/Readmes.md): generate README files for the tasks/workflows in a WDL file - these files are named so that they will be recognized when building DNAnexus apps/workflows using [dxWDL](https://github.com/dnanexus/dxWDL)
* [upgrade](doc/Commands/Upgrade.md): upgrade a WDL file to a newer version; currently only draft-2 -> 1.0 is supported


## Limitations

* Forward references are not yet supported, i.e. a variable must be declared before it is referenced
* Incomplete linter rules
* Runtime and hint attributes cannot be [overriden at runtime](https://github.com/openwdl/wdl/pull/315/files#diff-7ab1be25d3b4d9ecf4f763e14d464681R3029) in the task runner

## Building

The java code for the parser is generated by the [ANTRL4](https://www.antlr.org) tool.

1. Download a jar file with the [instructions](https://www.antlr.org/download.html)
2. `cd GIT_REPO`
3. Assuming you downloaded the jar file to `$HOME/antlr-4.8-complete.jar` generated java code from the grammar by running `make` from the toplevel.
4. Build the scala code:
    ```
    sbt compile
    ```
5. Run the tests:
    ```
    sbt test
    ```

See the [documentation](doc/Developing.md) for full details.

## Support

wdlTools is _not_ an official product of DNAnexus. Please do not contact DNAnexus (or any employees thereof) for support. To report a bug or feature request, please open an issue in the [issue tracker](https://github.com/dnanexus-rnd/wdlTools/issues).
