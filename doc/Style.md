# WDL Style

Style decisions are made with the following goals in mind:

* Be as consistent as possible with the style used in the [WDL specification](https://github.com/openwdl/wdl/blob/master/versions/1.0/SPEC.md).
* Produce the smallest diffs possible to make code review faster.
* Consistent with Python naming/formatting, since WDL syntax is primarily inspired by Python

This document does *not* specify formatting rules for code within task command blocks. Command code should follow best-practice formatting rules for the language in which it is written (typically, bash).

## Indentation

* Top-level elements (version, import, struct, workflow, task) should not be indented
* Each level of nesting should be indented an additional 2 spaces
* What should be indented?
    * Anything within a set of braces (`{}`) or brackets (`[]`), unless the opening and closing braces/brackets are on the same line
        ```wdl
        Map[String, Array[Int]] x = {
          "a": [1, 2, 3]
        }
        ```
    * Inputs following `input:` in a call block
        ```wdl
        call {
          input:
            x = 1,
            y = 2
        }  
        ```
    * Continuations of expressions that do not fit on a single line
* It is recommended to indent the command block to match the indenting of other blocks

### Example

```wdl
version 1.0

workflow GreetWorkflow {
  input {
    String name
    String? salutation
  }
 
  call Greet {
    input:
      name = name,
      salutation = select_first([
        salutation,
        "hello"
      ])
  }

  output {
    String greeting = Greet.greeting
  }
}

task Greet {
  input {
    String name
    String salutation
  }

  String message = "${actual_salutation} ${name}!"

  command <<<
    echo '~{message}'
  >>>
  
  output {
    String greeting = message
  }

  runtime {
    docker: "ubuntu"
  }

  parameter_meta {
    salutation: {
      help: "The salutation to use in the greeting"
      choices: [
        "hello",
        "hey",
        "yo"
      ]
    }
  }
}
```

## Line breaks/wrapping

* The maximum line width is 100 characters
    * You may choose to use a lesser maximum line width (e.g. values of 79, 80, and 88 are popular)
* When possible, wrap lines longer than the maximum length
    * WDL does not have a facility for wrapping long strings - instead, use interpolation when possible
        ```wdl
        # ok
        String s = "writing output to /my/long/filename.txt"
        # better
        File f = "/my/long/filename.txt"
        String s = "writing output to ${f}"
        ```
* Blocks
    * Opening brace (`{`) is always followed by a newline
    * Closing brace (`}`) is always on a line by itself, at the same level of indentation as its matching opening brace
* Collection literals (Array, Pair, Map, Object)
    * An `Array`- or `Pair`-literal should be on a single line, unless it is longer than the maximum line width
    * A `Map`- or `Object`-literal should be wrapped the same as a block, but it is acceptable to put it on a single line, if it is shorter than the maximum line width, and especially if it has a single element
    * When wrapping a collection literal, each element should be on a separate line
    ```wdl
    Array[Int] a = [1, 2, 3]
    Array[String] s = [
      "a long long string",
      "another long long string"
    ]
    Pair[String, Int] p = ("x", 1)
    Map[String, Boolean] m = {
      "good": true,
      "bad": false,
      "ugly": false
    }
    # acceptable
    Object o = object {a: 1.0}
    ```
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

* Maximum of one blank line between statements/blocks
* Always put blank lines between sections at the same level of indentation
* Optionally, blank lines may be used to group different sets of variables/keys within the same block
    ```wdl
    input {
      File input_file_1
      File input_file_2
  
      Int opt1
      String opt2
    }
    ```

## Comments

WDL supports single-line comments appearing anywhere on a line, including at the end of a line of code. Comments start with the `#` character.

* Do not place comments before the `version` statement
* Indent full-line comments at the same level as the next non-comment line
* End-of-line comments should have two spaces between the end of the code and the comment
* Wrap comments at the same maximum line width as the code

wdlTools supports additional comment syntax that is not a part of the WDL specification:

* Pre-formatted comments begin with two hashes ('##') - these comments are not reformatted by the code formatter
    ```wdl
    version 1.0
    
    ## This comment is not re-formatted
    ##   blah blah blah
    task foo { ... }
    ```

### Documentation comments

The wdlTools [docgen](Commands/Docgen.md) tool normally ignores comments. However, you may use the documentation comment syntax to provide comments that the documentation tool will recognize and include in the generated documentation.

* A comment that begins with three hashes ('###') is a documentation comment (it is also treated as a pre-formatted comment and so will not be reformatted by the code formatter.)
    ```wdl
    version 1.0
  
    ### This is a documentation comment
    task foo { ... }
    ```
* A documentation comment is always associated with the element that follows it immediately on the next line (i.e. there can be no blank lines between the documentation comment and the element it is annotating).
  * The only exception to this is a top-level comment, which must appear immediately following the 'version' statement and be preceeded and followed by blank lines:
    ```wdl
    version 1.0
    
    ### This is a top-level comment
    ### that describes the entire WDL file
    
    ### This comment refers to the following task
    task foo { ... }
    ```

## Expressions

* Operators
  * Put a space on either side of binary operators
  * Unary operators must not be followed by whitespace
  ```wdl
  Int i = 1 + 1
  Int j = -2
  ```
* Parentheses
    * Always use to group operations (don’t assume everyone has memorized the order of operations)
    * Space before (but not after) left-paren, unless it is preceded by a function name
    * Space after (but not before) right-paren, unless it is the last character on a line (e.g. when wrapping long expressions)
    ```wdl
    Int i = (1 + 1) * 2
    Int j = select_first([i, 1])
    ```
* A comma is always followed by a space, unless it is the last character on a line (e.g. when wrapping long expressions)

## WDL File Organization

1. version (`1.0` or `development`)
    - wdlTools recognizes `draft-3` as a synonym for `1.0` and `2.0` as a synonym for development, but this is not (yet) universal
1. imports
1. structs
1. workflow
    1. input
    1. non-input declarations
    1. call/scatter/conditionals/declarations in logical order
    1. output
    1. meta
    1. parameter_meta
1. tasks
    1. input
    1. non-input declarations
    1. command
    1. output
    1. runtime
    1. meta
    1. parameter_meta

## Data Types

* Complex data types
    * Always use `Struct`s; never use `Object` types (the `Object` type is deprecated in WDL `development`/`2.0`)
    * Avoid using `Pair` or `Map` types unless absolutely necessary - almost all use cases for these are better satisfied by (arrays of) structs
* Avoid using `Directory` for now, even when using version `development`/`2.0`, as directory types are not supported well (or at all) by workflow engines
* Prefer `ceil` or `floor` for converting `Float` to `Int`, as their behaviors are more explicit than `round`
* Prefer string interpolation to concatenation
    ```wdl
    # Good
    File output_file = "${foo}.txt"
    
    # Bad
    File output_file = foo + ".txt"
    ```
* Whenever possible, be explicit when converting between types, i.e. try not to rely on the automatic coersion rules.
    * Automatic coercion can be surprisingly complicated, and there may be edge cases where different execution engines evaluate automatic coercions differently.
    ```wdl
    # Good
    String? s1 = "foo"
    String s2 = select_first([s1])
    # Bad
    String? s1 = "foo"
    String s2 = s1
    ```

## Task Sections

* Input
    * Input parameters should have the same name as their corresponding command-line options in the command block (where applicable)
    * Avoid using optional or required arrays (e.g. `Array[String]?` or `Array[Int]+`) when writing workflows that will be compiled using dxWDL to run on DNAnexus: DNAnexus app/workflow inputs do not distinguish between empty and null inputs.
* Command Block
    * Always use `command <<< >>>` with `~{...}` style placeholders
    * Begin with `set -uexo pipefail`
    * Use comments liberally
* Output
    * Files
        * Specify file names explicitly when there are a small number of output files
        * When using `glob()` to return an array of files, write your command block to place the output files in a separate directory, to avoid accidentally selecting additional files
        ```wdl
        command <<<
        mkdir output
        for i in 1..10; do
          ./my_command ~{input_file} $i output/output_file_$i
        done
        >>>
        
        output {
          Array[File] outputs = glob("output/*")
        }
        ```
* Runtime
    * Be explicit about resource requirements if they are known; e.g. if a single CPU is required, specify `cpu: 1` rather than omitting `cpu`.
    * When resource requirements are not known, provide input parameters for any resource requirements that may vary, and/or take advantage of the standard library to determine resource requirements at runtime:
    ```wdl
    task Align {
      input {
        File bam
        Int cpu = 4
        Int memory_gb = 8
      }
  
      Int disk_size_gb = ceil(size(bam, "G") * 2)
  
      ...
      
      runtime {
        cpu: cpu
        memory: "${memory_gb} G"
        disks: "local-disk ${disk_size_gb} SSD"
      }
    }
    ```
