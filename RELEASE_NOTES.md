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