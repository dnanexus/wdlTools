# Upgrade command

The `upgrade` command upgrades a WDL file written in an older version of WDL to a newer version. Currently, it is only supported to upgrade draft-2 to 1.0.

## Usage

```commandline
Usage: wdlTools upgrade [OPTIONS] <path|uri>
Upgrade a WDL file to a more recent version

Options:
  -d, --dest-version  <arg>   WDL version of the document being upgraded
  -f, --follow-imports        format imported files in addition to the main file
      --nofollow-imports      only format the main file
  -l, --local-dir  <arg>...   directory in which to search for imports; ignored
                              if --noFollowImports is specified
  -o, --output-dir  <arg>     Directory in which to output upgraded WDL file(s);
                              if not specified, the input files are overwritten
      --overwrite
      --nooverwrite
  -s, --src-version  <arg>    WDL version of the document being upgraded
  -h, --help                  Show help message

 trailing arguments:
  url (required)   path or URL (file:// or http(s)://) to the main WDL file
```