# Docgen

The `docgen` command generates documentation for your WDL file(s) based on a template. The default template generates markdown files that are compatible with sites like GitHub and ReadTheDocs.

## Usage

```
Usage: wdlTools docgen [OPTIONS] <path|uri>
Generate documentation from a WDL file and all its dependencies

Options:

  -f, --follow-imports        (Default) format imported files in addition to the
                              main file
      --nofollow-imports      only format the main file
  -l, --local-dir  <arg>...   directory in which to search for imports; ignored
                              if --nofollow-imports is specified
  -O, --output-dir  <arg>     Directory in which to output documentation
  -o, --overwrite             Overwrite existing files
      --nooverwrite           (Default) Do not overwrite existing files
  -t, --title  <arg>          Title for generated documentation
  -h, --help                  Show help message

 trailing arguments:
  url (required)   path or URL (file:// or http(s)://) to the main WDL file

```

## Example 

You can very easily generate and preview your documentation using [mkDocs](https://mkdocs.org).

1. Generate your documentation into the `docs` folder:
    ```commandline
    $ java -jar wdlTools.jar docgen -O docs --overwrite my.wdl
    ```
2. [Install mkDocs](https://www.mkdocs.org/#installation), for example
    ```
    $ pip install mkdocs
    ```
3. In the same directory that contains the `docs` folder, add a `mkdocs.yml` file:
    ```yaml
    # mkdocs.yml
    site_name: simple
    
    theme:
      name: mkdocs
    
    nav:
      - Home: index.md
    
    markdown_extensions:
      - toc:
          permalink: 
    ```
4. Run `mkdocs serve`
5. Browse to `http://127.0.0.1:8000/`