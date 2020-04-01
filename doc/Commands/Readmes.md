# Readmes

This command generates README files for your workflows and tasks in the format expected by [dxWDL](https://github.com/dnanexus/dxWDL). Specifically, for each WDL file, it generates one README file for each workflow with name `Readme.<wdl name>.<workflow name>.md`, and one README file for each task with the name `Readme.<wdl name>.<task name>.md`.

With the `--developer-readmes` option, you may also generate developer README files (`Readme.developer.<wdl name>.<task|workflow name>.md`). 

By default, each README file is placed in the same directory as its associated WDL file. However, if you specify `--output-dir`, all files will be generated in that directory instead.

## Usage

```commandline
Usage: wdlTools readmes [OPTIONS] <path|uri>
Generate README file stubs for tasks and workflows.

Options:
  -d, --developer-readmes     also generate developer READMEs
      --nodeveloper-readmes
  -f, --follow-imports        format imported files in addition to the main file
      --nofollow-imports      only format the main file
  -o, --output-dir  <arg>     Directory in which to output formatted WDL files;
                              if not specified, the input files are overwritten
      --overwrite
      --nooverwrite
  -h, --help                  Show help message

 trailing arguments:
  uri (required)   path or URI (file:// or http(s)://) to the main WDL file
```