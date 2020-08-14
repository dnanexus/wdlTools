# Exec

The `exec` command executes a WDL task on the local computer. Workflow execution is not yet supported.

## Usage

```commandline
Usage: wdlTools exec [OPTIONS] <path|uri>
Execute a WDL task

Options:

  -c, --container                 Execute the task using Docker (if applicable)
      --nocontainer               Do not use Docker - all dependencies must be
                                  installed on the local system
  -i, --inputs  <arg>             Task inputs JSON file - if not specified,
                                  inputs are read from stdin
  -l, --local-dir  <arg>...       directory in which to search for imports;
                                  ignored if --nofollow-imports is specified
  -o, --outputs  <arg>            Task outputs JSON file - if not specified,
                                  outputs are written to stdout
  -q, --quiet                     use less verbose output
  -r, --runtime-defaults  <arg>   JSON file with default runtime values
  -s, --summary  <arg>            Also write a job summary in JSON format
  -t, --task  <arg>               The task name - requred unless the WDL has a
                                  single task
  -v, --verbose                   use more verbose output
  -h, --help                      Show help message

 trailing arguments:
  uri (required)   path or String (file:// or http(s)://) to the main WDL file
```