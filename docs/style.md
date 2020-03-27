# WDLkit Style

Style decisions are made with the following goals in mind:

* Be as consistent as possible with the style used in the [WDL specification](https://github.com/openwdl/wdl/blob/master/versions/1.0/SPEC.md).
* Produce the smallest diffs possible to make code review faster.

This document does *not* specify formatting rules for code within task command blocks. Command code should follow best-practice formatting rules for the language in which it is written (typically, bash).

## Indentation

* 2 spaces
* What should be indented?
    * Anything within a set of braces (`{}`)
    * Inputs following `input:` in a call block
    * Continuations of expressions which did no fit on a single line

## Line breaks

* Maximum of 100 characters
    * Wrap lines longer than the maximum length
* Opening brace (`{`) is always followed by a newline
* Closing brace (`}`) is always on a line by itself, at the same level of indentation as its matching opening brace
* Place line breaks logically, in the following order of preference:
    * Following a comma
    * Following an opening paren
    * Following an assignment (`=`)
    * Before the `then` or `else` in an `if-then-else` expression
    * Following an operator that would otherwise be followed by a space
* When wrapping an expression (or part of an expression), place the entire wrapped portion in parentheses, with the opening and closing parens following the same rules as braces and all wrapped lines being indented
    
    ```wdl
    Pair[Int, String] i = (
      select_first([foo, 1]),
      select_first([bar, "hello"])
    )
    ```
## Casing

* WDL file names: `snake_case.wdl`
* Task, workflow, and struct names: `UpperCamelCase` 
* Variable names, calls, aliases: `snake_case`

## Blank lines

* Only one line
* Always between sections at the same level of indentation
* Optionally, may be used to group different sets of variables/keys within the same block

    ```wdl
    input {
      File input_file_1
      File input_file_2
  
      Int opt1
      String opt2
    }
    ```

## Expressions

* Space on either side of all operators except
    * Unary operators (`+`, `-`)
    * Logical NOT (`!`)
* Parentheses
    * Always use to group operations (don’t assume everyone has memorized the order of operations)
    * Space before (but not after) left-paren, unless it is preceded by a function name
    * Space after (but not before) right-paren, unless it is the last character on a line (e.g. when wrapping long expressions)
* A comma is always followed by a space, unless it is the last character on a line (e.g. when wrapping long expressions)

## WDL File Organization

1. version (`1.0` or `development`)
1. imports
1. structs
1. workflow
    1. input
    1. non-input variables
    1. call/scatter/conditionals in logical order
    1. output
    1. meta
    1. parameter_meta
1. tasks
    1. input
    1. non-input variables
    1. command
    1. output
    1. runtime
    1. meta
    1. parameter_meta

## Data Types

* Complex data types
    * Always use `Struct`s; never use `Object` types
    * Avoid using `Pair` or `Map` types unless absolutely necessary - almost all use cases for these are better satisfied by (arrays of) structs
* Avoid using `Directory` for now, even when using version `development`, as directory types are not supported well (or at all) by workflow engines
* Prefer `ceil` or `floor` for converting `Float` to `Int`, as their behaviors are more intuitive than `round`
* Prefer string interpolation to concatenation

    ```wdl
    # Good
    File output_file = "${foo}.txt"
    
    # Bad
    File output_file = foo + ".txt"
    ```

## Task Sections

* Input
    * Input parameters should have the same name as their corresponding command-line options (where applicable)
    * Avoid using optional or required arrays (e.g. `Array[String]?` or `Array[Int]+`) when writing workflows that will be compiled using dxWDL to run on DNAnexus: DNAnexus app/workflow inputs do not distinguish between empty and null inputs.
* Command Block
    * Always use `command <<< >>>` with `~{...}` style placeholders
    * Begin with `set -uexo pipefail`
    * Use comments liberally
* Output
    * Files
        * Specify file names explicitly when there are a small number of output files
        * When using `glob()` to return an array of files, write your command block to place the output files in a separate directory, to avoid accidentally selecting additional files
* Runtime
    * Be explicit about resource requirements if they are known; e.g. if a single CPU is required, specify `cpu: 1` rather than omitting `cpu`.∑∑