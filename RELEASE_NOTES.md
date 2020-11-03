# Change log

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