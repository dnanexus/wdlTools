# Check

The `check` command performs type-checking on a WDL file.

## Usage

```commandline
Usage: wdlTools check [OPTIONS] <path|uri>
Type check WDL file.

Options:

  -a, --antlr4-trace          enable trace logging of the ANTLR4 parser
  -j, --json                  Output type-check errors as JSON
      --nojson                Output type-check errors as plain text
  -l, --local-dir  <arg>...   directory in which to search for imports; ignored
                              if --nofollow-imports is specified
  -o, --output-file  <arg>    File in which to write type-check errors; if not
                              specified, errors are written to stdout
      --overwrite             Overwrite existing files
      --nooverwrite           (Default) Do not overwrite existing files
  -q, --quiet                 use less verbose output
  -r, --regime  <arg>         Strictness of type checking
  -v, --verbose               use more verbose output
  -h, --help                  Show help message

 trailing arguments:
  uri (required)   path or String (file:// or http(s)://) to the main WDL file
```