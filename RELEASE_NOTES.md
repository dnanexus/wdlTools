# Change log

## in develop

* No longer unwraps optional type for an input with a default value
* Automatically changes struct declaration with map literal assignment to an object literal 
* Adds `Runtime.returnCodes` accessor
* Fixes formatting of single-line expressions in code generator/formatter

## 0.14.3 (2021-07-06)

* Code generator/formatter handles empty blocks
* Handles accessing object fields within placeholders
* Allows comparison of object fields to `String`

## 0.14.2 (2021-06-28)

* Updates dxCommon version, which fixes the output of the `printTree` command
* Allow primitive -> `String` coercion for WDL versions <= 1.0
* Fixes parsing of unary operations
* A `parameter_meta` key that does not correspond to an input or output parameter is now ignored with a warning
* Fixes caching of imports during parsing - now absolute rather than relative file paths are used 
* Fixes case where the `fix` tool would generate an invalid declaration

## 0.14.1 (2021-06-08)

* Allows automatic coersion of `read_*` return values from `String` to other primitive types
* Allows automatic coercion of optional-typed expressions to String within placeholders
* Fixes handling of relative imports that are not in a subdirectory of the main WDL's parent directory

## 0.14.0 (2021-05-27)

* Adds `fix` tool to automatically fix some non-compliant WDL syntax
* Improve error messages in expression evaluator

## 0.13.1 (2021-05-27)

* Fixes handling of relative imports for WDL specified using non-file URIs (e.g. http(s))
* Fixes quoting and escaping of import URIs by code formatter/generator
* Disables wrapping of expressions within placeholders

## 0.13.0 (2021-05-20)

* **Breaking changes**:
    * `loc` is moved from the first to second parameter section in all AST case classes to prevent it from being included in the `equals` and `hashCode` implementations
    * Some fields of AST case classes are renamed for greater clarity
* Allows concatenation of `String` and non-`String` variables in draft2 and 1.x
* Captures type of quotes used in strings (single or double) so they can be recapitulated by the code formatter/generator
* Allows any type of expression to be used as the value of the `default` placeholder option
* Fixes code generator and formatter for nested placeholder edge-cases

## 0.12.11 (2021-05-07)

* Fixes regession in code generator and formatter

## 0.12.10 (2021-05-07)

* Escapes strings when generating/formatting code (unless they are in the command block)
* Always unwraps `V_Optional` values during expression evaluation
* Fixes issues with using expressions in placeholder option values

## 0.12.9 (2021-04-19)

* Fix resolution of imports using local paths not relative to the main document

## 0.12.8 (2021-04-19) 

* Adds support for Object -> Map coercion
* Fixed indentation issue in command block
* Fixes processing of escape sequences (including regular expressions used in second argument to `sub`)  
* Other bugfixes
* Updates to dxCommon 0.2.12

## 0.12.7 (2021-03-17)

* **Breaking change**: replaces individual placeholder AST/TST elements with a single unified placeholder element
* Adds support for non-compliant (but commonly used) combinations of placeholder options (default together with true/false or sep)
* Handle 'Left' and 'Right' as keys for serialized `Pair`s
* Emit warning rather than throw exception when there are duplicate keys in runtime/hints/meta sections

## 0.12.6 (2021-02-25)

* Allow coercion of `read_lines` return value to array of any primitive type
* Update to dxCommon 0.2.9

## 0.12.5 (2021-02-19)

* Preserve blank lines in command section

## 0.12.4 (2021-02-17)

* Add back placeholder support for v1.1

## 0.12.3 (2021-02-08)

* Fix WDL v1.1 parsing
* Add `TypeInfer.applyExpression` function for type.checking expressions
* Duplicate declaration names in different task/workflow sections are now caught during type-checking

## 0.12.2 (2021-02-4)

* Refactor `wdlTools.exec.InputOutput`
* Add convenience constructors for compound WdlValues
* Fix issue in `WdlGenerator` when first non-whitespace in command block is a placeholder

## 0.12.1 (2021-02-03)

* Bugfixes

## 0.12.0 (2021-02-02)

* Adds support for WDL 1.1

## 0.11.18 (2020-01-29)

* Better handling of fully-qualified names in evaluation context

## 0.11.17 (2020-01-27)

* Fix bug in evaluation of nested placeholders

## 0.11.16 (2020-01-20)

* Revert change that disallowed String + non-String concatenation and Boolean-Boolean comparisons pre-2.0
* Fix some places where `SortedMap` wasn't being used when serializing
* Use `SeqMap` for compound values (`Struct` and `Object`)
* Use consistent terminology for compound types (Array and Map *items*; Struct and Object *fields*)

## 0.11.15 (2020-01-14)

* Allow any compound type to be aliased
* Enable recursive deserialization of types
* Support use of type aliases for WDL type (de)serialization
* Use `SortedMap`s when serializing to JSON to the results are consistent

## 0.11.14 (2020-01-13)

* Handle special case of null values for non-optional compound inputs in `wdlTools.exec.InputOutput`
* The JSON serialization format was updated for compound types:
    * "name" -> "type"
    * For Array, "itemType" -> "items"  
    * For Map, "keyType" -> "keys" and "valueType" -> "values"
    * For Pair, "leftType" -> "left" and "rightType" -> "right"

## 0.11.13 (2020-01-12)

* Bump ANTLR version to 4.9

## 0.11.12 (2020-01-05)

* Revert change to `Stdlib.size` - don't use `isExactDobule`

## 0.11.11 (2020-01-05)

* Fix several small issues that have recently been clarified in the spec

## 0.11.10 (2020-12-16)

* Bugfixes

## 0.11.9 (2020-12-15)

* Move DockerUtils to dxCommon

## 0.11.8 (2020-12-01)

* Fix unification of arrays

## 0.11.7 (2020-12-01)

* Throw type error when map keys are non-primitive
* Fix bug with vectorization of subtraction and division operations

## 0.11.6 (2020-11-25)

* Fix more code generator/formatter bugs, add tests

## 0.11.5 (2020-11-25)

* Fix code generator/formatter for vectorized operators
* Move LocalizationDisambiguation to dxCommon

## 0.11.4 (2020-11-24)

* Vectorize mathematical functions - successive `+`, `-`, `*`, and `/` operations are transformed from nested pairwise function calls to a single function call on a Vector of arguments 
* Fixed parser issue in development where runtime keys (e.g. `cpu`, `container`) could not be used as variable names

## 0.11.3 (2020-11-23)

* Add base class for "generic" user-defined functions

## 0.11.2 (2020-11-23)

* Add `SourceLocation` to `UserDefinedFunctionImplFactory.getImpl` signature
* Fix parsing of comments in *meta sections in WDL v2

## 0.11.1 (2020-11-20)

* Add `EvalPaths` to `FunctionContext`

## 0.11.0 (2020-11-20)

* Implement new syntax in development for pass-through of call arguments 
* Fix bug with struct-typed objects in v1
* Add hooks for user-defined functions

## 0.10.7 (2020-11-16)

* Relax type-checking of arrays - ignore nonEmpty, which is only a runtime check
* Fix code generator/formatter to only use object literal syntax in WDL 2+
* Add option `LocalizationDisambiguator` to always create new localization dir for each source dir

## 0.10.6 (2020-11-14)

* Add support for URI-formatted image names (e.g. 'docker://myimg')
* Add functions for serialization and deserialization of WdlTypes

## 0.10.5 (2020-11-12)

* Throw `NoSuchParserException` when a document is not detected to be parsable

## 0.10.4 (2020-11-10)

* Fix sevaral issues with the handling of non-empty arrays

## 0.10.3 (2020-11-09)

* Throw exception when there are key collisions in `as_map` function
* Other bugfixes

## 0.10.2 (2020-11-06)

* Change all map-types attributes of typed AST classes to be `SortedMap`s - mainly to preserve insert order for the code formatter
* Updated to dxCommon 0.2.1, which fixes a bug with idential AST/TST objects not comparing as equal/generating identical hash codes

## 0.10.1 (2020-11-05)

* Add support for struct literals in the code generator
* Add support for direct resolution of fully qualified names during evaluation of identifiers of pair, object, and call types

## 0.10.0 (2020-11-03)

* Implemented two accepted spec changes in WDL development/2.0:
    * [openwdl #243](https://github.com/openwdl/wdl/pull/243): Use RE2 for regular expression matching in `sub()` function
    * [openwdl/wdl#297](https://github.com/openwdl/wdl/pull/297): Struct literals

## 0.9.0 (2020-11-02)

* Moved common code (wdlTools.util.*) to separate package, dxCommon
* Fixed struct aliasing bug

## 0.8.4 (2020-10-22)

* Bugfixes for code generator/formatter

## 0.8.3 (2020-10-14)

* Bugfixes

## 0.8.2 (2020-10-11)

* Bugfixes

## 0.8.1 (2020-10-10)

* Bugfixes

## 0.8.0 (2020-10-09)

* Added support for new WDL v2 `min` and `max` functions
* Complete WDL v2 support in code formatter and generator
* Internal refactorings (will causes breaking changes for users of the library)
* Correctly handle optional parameters in task calls
* Fix JDK11 deprecation error
* Many bugfixes

## 0.7.1 (2020-09-29)

* Type-checking bug fixes
* Error message improvements

## 0.7.0 (2020-09-28)

* Refactoring of `types.Unification`, including additionl UnificationContext, which allows for context-specific type checking
* Renamed some members of typed AST:
    * `Declaration` -> `PrivateVariable`
    * `*InputDefinition` -> `*InputParameter`
    * `OutputDefinition` -> `OutputParameter`
    * `Task.declarations` -> `Task.privateVariables`
* Renamed `types.Context` -> `types.TypeContext`    
* Add default values for all reserved runtime keys (as per the [PR](https://github.com/openwdl/wdl/pull/315))
* Override `LocalFileSource.equals` to compare based on `localPath`    
* Renamed Utils classes
    * `eval.Utils` -> `eval.EvalUtils`
    * `syntax.Utils` -> `syntax.SyntaxUtils`
    * `types.Utils` -> `types.TypeUtils`

## 0.6.1 (2020-09-24)

* Support floating-point values for cpu, memory, and disk size runtime requirements
* Add `BlockElement` as the parent type of `Conditional` and `Scatter` in typed AST
* Logger enhancements
 
## 0.6.0 (2020-09-14)

* Fixed autodoc templates
* Allow T -> T? coercion under `Moderate` type-checking strictness
* Handle nested placeholders during string interpolation - these were previously disallowed
* Add expression graph builder for workflows

## 0.5.0 (2020-09-01)

* Added `wdlTools.types.Graph` for building DAGs from the TST.
* Breaking change: the operator case classes have been removed from AST and TST. Operators are now represented by `ExprApply` with special names. The names can be looked up in `wdlTools.syntax.Operator`.
* Many other internal breaking changes - mostly reorganization/renaming.

## 0.4.0 (2020-07-16)

* Add stand-alone task executor, and `exec` command

## 0.3.0 (2020-07-07)

* Add new WDL v2 `sep` function
* Move `FileAccessProtocol` to `util` package
* Unify all file handling in `FileAccessProtcol` - merge functionality from `SourceCode` and from dxWDL `Furl` classes
* Migrate some Docker utilities from dxWDL to wdlTools
* Rename `TextSource` -> `SourceLocation`
* Rename `text` AST fields to `loc`
* Add source document to `SourceLocation`
* Fix `Util.replaceSuffix`
* Other bugfixes

## 0.2.0 (2020-06-26)

* Cleaned up utility functions
* Migrated some utility code from dxWDL

## 0.1.0 (2020-06-25)

* First release