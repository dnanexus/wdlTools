# Format command

The `format` command reformats a WDL file (and optionally all its dependencies as well) according to style rules. The default formatter uses the DNAnexus [WDL style](style.md). The `format` command is highly opinionated, and thus there are no options for adjusting the style - to do so, you have to [write your own custom formatter](#custom).

## Technical details

The formatter works in several stages. First, it the WDL file is parsed into an [Abstract Syntax Tree (AST)](). Second, the AST is converted into a more coarse-grained [Document Structure Tree (DST)](#document-structure-tree). The formatter than turns the DST into a series of formatted lines that are written to the output file.

## Document Structure Tree

The Document Structure Tree (DST) is a coarse-grained representation of a WDL document. It has the following types:

* `Node`: the root type of all elements in the tree
* `Token`: a `Node` that has a String value; examples of `Token`s are WDL keywords, symbols (e.g. '+', '='), and individual elements of expressions (e.g. in the expression `1 + 2`, `1`, `+`, and `2` are all `Token`s).
* `Statement`: a `Node` that represents a single logical line of a WDL file; it is a collection of other `Node`s
* `Section`: a `Node` that represents a group of `Statement`s that go together; for example, the import `Section` is a collection of `import` statements 
* `Group`: a `Node` that is a collection of other `Node`s and is usually (but not always) surrounded by a pair of `Token`s. There is also an optional delimiter that separates the items in the body (e.g. an array literal is represented as a Group)
* `DelimitedGroup`: a Group in which all the elements are delimited by a specific `Token`
* `Block`: both a `Statement` and a `Section`; a `Block` starts with a keyword, has an optional clause `Group`, and a required body `Group`.
* `DataType`: a `Group` that represents a (possibly nested) WDL type.
* `Expression` a `Group` that represents a WDL expression