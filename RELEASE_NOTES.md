# v0.5.1 (2020-09-14)

* Fixed autodoc templates
* Allow T -> T? coercion under `Moderate` type-checking strictness
* Handle nested placeholders during string interpolation - these were previously disallowed
* Add expression graph builder for workflows

# v0.5.0 (2020-09-01)

* Added `wdlTools.types.Graph` for building DAGs from the TST.
* Breaking change: the operator case classes have been removed from AST and TST. Operators are now represented by `ExprApply` with special names. The names can be looked up in `wdlTools.syntax.Operator`.
* Many other internal breaking changes - mostly reorganization/renaming.

# v0.4.0 (2020-07-16)

* Add stand-alone task executor, and `exec` command

# v0.3.0 (2020-07-07)

* Add new WDL v2 `sep` function
* Move `FileAccessProtocol` to `util` package
* Unify all file handling in `FileAccessProtcol` - merge functionality from `SourceCode` and from dxWDL `Furl` classes
* Migrate some Docker utilities from dxWDL to wdlTools
* Rename `TextSource` -> `SourceLocation`
* Rename `text` AST fields to `loc`
* Add source document to `SourceLocation`
* Fix `Util.replaceSuffix`
* Other bugfixes

# v0.2.0 (2020-06-26)

* Cleaned up utility functions
* Migrated some utility code from dxWDL

# v0.1.0 (2020-06-25)

* First release