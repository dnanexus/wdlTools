# Lint

Checks a WDL document for common errors and code "smells." The linter applies a set of rules to the document - any rule that fails generates either a warning or an error, depending on how the rule is configured.

## Usage

```commandline
Usage: wdlTools lint [OPTIONS] <path|uri>
Check WDL file for common mistakes and bad code smells

Options:

  -c, --config  <arg>             Path to lint config file
  -e, --exclude-rules  <arg>...   Lint rules to exclude
  -f, --follow-imports            (Default) format imported files in addition to
                                  the main file
      --nofollow-imports          only format the main file
  -i, --include-rules  <arg>...   Lint rules to include; may be of the form
                                  '<rule>=<level>' to change the default level
  -j, --json                      Output lint errors as JSON
      --nojson                    Output lint errors as plain text
  -l, --local-dir  <arg>...       directory in which to search for imports;
                                  ignored if --nofollow-imports is specified
  -o, --output-file  <arg>        File in which to write lint errors; if not
                                  specified, errors are written to stdout
      --overwrite                 Overwrite existing files
      --nooverwrite               (Default) Do not overwrite existing files
  -h, --help                      Show help message

 trailing arguments:
  url (required)   path or URL (file:// or http(s)://) to the main WDL file
```

## Rules

### White space

* (P001) whitespace_tabs: Use of tabs in whitespace
* (P002) odd_indent: Indent by odd number of whitespace characters
* (P003) multiple_blank_lines: More than one blank line between sections
* (P004) top_level_indent: A top-level element is indented
* (P005) deprecated_command_style: Deprecated command 
* block style - use ~{} instead

### Other

* (A001) non_portable_task: A task with no runtime section, or with a runtime section that lacks a container definition
* (A002) no_task_inputs: Suspicious lack of task inputs (is this task really idempotent and portable?)
* (A003) no_task_outputs: Suspicious lack of task outputs (is this task really idempotent and portable?)