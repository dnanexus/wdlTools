# Change log

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

## v0.11.12 (2020-01-05)

* Revert change to `Stdlib.size` - don't use `isExactDobule`

## v0.11.11 (2020-01-05)

* Fix several small issues that have recently been clarified in the spec

## v0.11.10 (2020-12-16)

* Bugfixes

## v0.11.9 (2020-12-15)

* Move DockerUtils to dxCommon

## v0.11.8 (2020-12-01)

* Fix unification of arrays

## v0.11.7 (2020-12-01)

* Throw type error when map keys are non-primitive
* Fix bug with vectorization of subtraction and division operations

## v0.11.6 (2020-11-25)

* Fix more code generator/formatter bugs, add tests

## v0.11.5 (2020-11-25)

* Fix code generator/formatter for vectorized operators
* Move LocalizationDisambiguation to dxCommon

## v0.11.4 (2020-11-24)

* Vectorize mathematical functions - successive `+`, `-`, `*`, and `/` operations are transformed from nested pairwise function calls to a single function call on a Vector of arguments 
* Fixed parser issue in development where runtime keys (e.g. `cpu`, `container`) could not be used as variable names

## v0.11.3 (2020-11-23)

* Add base class for "generic" user-defined functions

## v0.11.2 (2020-11-23)

* Add `SourceLocation` to `UserDefinedFunctionImplFactory.getImpl` signature
* Fix parsing of comments in *meta sections in WDL v2

## v0.11.1 (2020-11-20)

* Add `EvalPaths` to `FunctionContext`

## v0.11.0 (2020-11-20)

* Implement new syntax in development for pass-through of call arguments 
* Fix bug with struct-typed objects in v1
* Add hooks for user-defined functions

## v0.10.7 (2020-11-16)

* Relax type-checking of arrays - ignore nonEmpty, which is only a runtime check
* Fix code generator/formatter to only use object literal syntax in WDL 2+
* Add option `LocalizationDisambiguator` to always create new localization dir for each source dir

## v0.10.6 (2020-11-14)

* Add support for URI-formatted image names (e.g. 'docker://myimg')
* Add functions for serialization and deserialization of WdlTypes

## v0.10.5 (2020-11-12)

* Throw `NoSuchParserException` when a document is not detected to be parsable

## v0.10.4 (2020-11-10)

* Fix sevaral issues with the handling of non-empty arrays

## v0.10.3 (2020-11-09)

* Throw exception when there are key collisions in `as_map` function
* Other bugfixes

## v0.10.2 (2020-11-06)

* Change all map-types attributes of typed AST classes to be `SortedMap`s - mainly to preserve insert order for the code formatter
* Updated to dxCommon 0.2.1, which fixes a bug with idential AST/TST objects not comparing as equal/generating identical hash codes

## v0.10.1 (2020-11-05)

* Add support for struct literals in the code generator
* Add support for direct resolution of fully qualified names during evaluation of identifiers of pair, object, and call types

## v0.10.0 (2020-11-03)

* Implemented two accepted spec changes in WDL development/2.0:
    * [openwdl #243](https://github.com/openwdl/wdl/pull/243): Use RE2 for regular expression matching in `sub()` function
    * [openwdl/wdl#297](https://github.com/openwdl/wdl/pull/297): Struct literals

## v0.9.0 (2020-11-02)

* Moved common code (wdlTools.util.*) to separate package, dxCommon
* Fixed struct aliasing bug

## v0.8.4 (2020-10-22)

* Bugfixes for code generator/formatter

## v0.8.3 (2020-10-14)

* Bugfixes

## v0.8.2 (2020-10-11)

* Bugfixes

## v0.8.1 (2020-10-10)

* Bugfixes

## v0.8.0 (2020-10-09)

* Added support for new WDL v2 `min` and `max` functions
* Complete WDL v2 support in code formatter and generator
* Internal refactorings (will causes breaking changes for users of the library)
* Correctly handle optional parameters in task calls
* Fix JDK11 deprecation error
* Many bugfixes

## v0.7.1 (2020-09-29)

* Type-checking bug fixes
* Error message improvements

## v0.7.0 (2020-09-28)

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

## v0.6.1 (2020-09-24)

* Support floating-point values for cpu, memory, and disk size runtime requirements
* Add `BlockElement` as the parent type of `Conditional` and `Scatter` in typed AST
* Logger enhancements
 
## v0.6.0 (2020-09-14)

* Fixed autodoc templates
* Allow T -> T? coercion under `Moderate` type-checking strictness
* Handle nested placeholders during string interpolation - these were previously disallowed
* Add expression graph builder for workflows

## v0.5.0 (2020-09-01)

* Added `wdlTools.types.Graph` for building DAGs from the TST.
* Breaking change: the operator case classes have been removed from AST and TST. Operators are now represented by `ExprApply` with special names. The names can be looked up in `wdlTools.syntax.Operator`.
* Many other internal breaking changes - mostly reorganization/renaming.

## v0.4.0 (2020-07-16)

* Add stand-alone task executor, and `exec` command

## v0.3.0 (2020-07-07)

* Add new WDL v2 `sep` function
* Move `FileAccessProtocol` to `util` package
* Unify all file handling in `FileAccessProtcol` - merge functionality from `SourceCode` and from dxWDL `Furl` classes
* Migrate some Docker utilities from dxWDL to wdlTools
* Rename `TextSource` -> `SourceLocation`
* Rename `text` AST fields to `loc`
* Add source document to `SourceLocation`
* Fix `Util.replaceSuffix`
* Other bugfixes

## v0.2.0 (2020-06-26)

* Cleaned up utility functions
* Migrated some utility code from dxWDL

## v0.1.0 (2020-06-25)

* First release