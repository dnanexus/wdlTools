# PrintTree Command

The `printTree` command prints the abstract syntax tree (AST) or typed AST (TST) for a WDL document.

## Usage

```
Usage: wdlTools printTree [OPTIONS] <path|uri>
Print the Abstract Syntax Tree for a WDL file.

Options:

  -t, --typed                 Print the typed AST (document must pass
                              type-checking)
      --notyped               Print the raw AST
  -h, --help                  Show help message

 trailing arguments:
  url (required)   path or URL (file:// or http(s)://) to the main WDL file
```