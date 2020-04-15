# New

The `new` command generates a new WDL project. A WDL project consists of a WDL file (which may contain a workflow and any number of tasks), and optionally a Dockerfile, README file(s), and tests (which depend on the [pytest-wdl]() testing framework). The command also generates a Makefile to automate common operations.

## Usage

```commandline
Usage: wdlTools new <task|workflow|project> [OPTIONS]
Generate a new WDL task, workflow, or project.

Options:
  -d, --docker  <arg>        The Docker image ID
      --dockerfile           Generate a Dockerfile template
      --nodockerfile         (Default) Do not generate a Dockerfile template
  -i, --interactive          Specify inputs and outputs interactively
      --nointeractive        (Defaut) Do not specify inputs and outputs
                             interactively
  -m, --makefile             (Default) Generate a Makefile
      --nomakefile           Do not generate a Makefile
  -o, --output-dir  <arg>    Directory in which to output formatted WDL files;
                             if not specified, ./<name> is used
      --overwrite            Overwrite existing files
      --nooverwrite          (Default) Do not overwrite existing files
  -r, --readmes              (Default) Generate a README file for each
                             task/workflow
      --noreadmes            Do not generate README files
  -t, --task  <arg>...       A task name
      --tests                (Default) Generate pytest-wdl test template
      --notests              Do not generate a pytest-wdl test template
  -w, --wdl-version  <arg>   WDL version to generate; currently only v1.0 is
                             supported
      --workflow             (Default) Generate a workflow
      --noworkflow           Do not generate a workflow
  -h, --help                 Show help message

 trailing arguments:
  name (required)   The project name - this will also be the name of the
                    workflow
```

