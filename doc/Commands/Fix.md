# Fix

The `fix` command attempts to automatically fix WDL files with the following specification incompatibilities:

* `String`/non-`String` concatenations are re-written as string interpolations, .e.g.
    ```wdl
    runtime {
      disks: "local-disk " + disk_size_gb + " SSD"
    }
    ```
    becomes
    ```wdl
    runtime {
      disks: "local-disk ${disk_size_gb} SSD"
    }
    ```
* Declarations with type mismatches, e.g.
    ```wdl
    String x = ceil(size(myfile))
    ```
    becomes
    ```wdl
    Int x = ceil(size(myfile))
    ```

Additional fixes will be added as needed. Please request them by opening an [issue](https://github.com/dnanexus-rnd/wdlTools/issues).

## Issues that cannot be fixed (partial list)

* Re-usage of variable names within the same scope, e.g.
    ```wdl
    task bad_juju {
      input {
        String foo
      }
      ...
      output {
        Int foo
      }
    }
    ```
* Coercions from `String` to non-`String` values are allowed within the context of `read_*` return values, but not otherwise. For example, this is legal:
    ```wdl
    task ok {
      command <<<
      echo "1\n2\n3" > ints.txt
      >>>
      output {
        Array[Int] ints = read_lines("ints.txt")
      }
    }
    ```
    but this is illegal:
    ```wdl
    workflow not_ok {
      call ints_to_strings
      output {
        Array[Int] ints = ints_to_strings.strings
      }
    }
    task ints_to_strings {
      command <<<
      echo "1\n2\n3" > ints.txt
      >>>
      output {
        Array[String] strings = read_lines("ints.txt")
      }
    }
    ```

## Example

```bash
$ java -jar target/wdlTools.jar fix -O gatk-fixed https://raw.githubusercontent.com/gatk-workflows/gatk4-genome-processing-pipeline/1.3.0/WholeGenomeGermlineSingleSample.wdl
```

## Usage

```commandline
Usage: wdlTools fix [OPTIONS] <path|uri>
Fix specification incompatibilities in WDL file and all its dependencies.

Options:

  -a, --antlr4-trace          enable trace logging of the ANTLR4 parser
  -b, --base-uri  <arg>       Base URI for imports; output directories will be
                              relative to this URI; defaults to the parent
                              directory of the main WDL file
  -f, --follow-imports        (Default) format imported files in addition to the
                              main file
      --nofollow-imports      only format the main file
  -l, --local-dir  <arg>...   directory in which to search for imports; ignored
                              if --nofollow-imports is specified
  -O, --output-dir  <arg>     Directory in which to output fixed WDL files;
                              defaults to current directory
  -o, --overwrite             Overwrite existing files
      --nooverwrite           (Default) Do not overwrite existing files
  -q, --quiet                 use less verbose output
  -s, --src-version  <arg>    WDL version of the document being upgraded
  -v, --verbose               use more verbose output
  -h, --help                  Show help message

 trailing arguments:
  uri (required)   path or String (file:// or http(s)://) to the main WDL file
```