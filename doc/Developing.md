# Developing wdlTools

## Setting up your development environment

* Install JDK 8
  * On mac with [homebrew]() installed:
    ```
    $ brew tap AdoptOpenJDK/openjdk
    $ brew cask install adoptopenjdk8
    # Use java_home to find the location of JAVA_HOME to set
    $ /usr/libexec/java_home -V
    $ export JAVA_HOME=/Library/Java/...
    ```
  * On Linux (assuming Ubuntu 16.04)
    ```
    $ sudo apt install openjdk-8-jre-headless
    ```
  * Scala will compile with JDK 11, but the JDK on DNAnexus worker instances is JDK 8 and will not be able to run a JAR file with classes compiled by a later version of Java
* Install [sbt](https://www.scala-sbt.org/), which also installs Scala. Sbt is a make-like utility that works with the ```scala``` language.
  * On MacOS: `brew install sbt`
  * On Linux:
    ```
    $ wget www.scala-lang.org/files/archive/scala-2.12.1.deb
    $ sudo dpkg -i scala-2.12.1.deb
    $ echo "deb https://dl.bintray.com/sbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list
    $ sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823
    $ sudo apt-get update
    $ sudo apt-get install sbt
    ```
  * Running sbt for the first time takes several minutes, because it downloads all required packages.
* We also recommend to install [Metals](https://scalameta.org/metals/), which enables better integration with your IDE
  * For VSCode, install the "Scala (Metals)" and "Scala Syntax (official)" plugins

## Getting the source code

* Clone or fork the [wdlTools repository](https://github.com/dnanexus-rnd/wdlTools) (depending on whether you have commit permissions)
* Checkout an existing branch or create a new branch (e.g. feat/42-my-feature)
* Add pre-commit hooks:
  * Create/edit a file .git/hooks/pre-commit
  * Add the following lines
    ```bash
    #!/bin/bash
    check=$(sbt scalafmtCheckAll)
    if [[ "$?" -ne "0" ]]; then
      echo "Reformatting; please commit again"
      sbt scalafmtAll
      exit $check
    fi
    ```
  * Make the file executable (e.g. `chmod +x .git/hooks/pre-commit`)
  * This hook runs the code formatter before committing code. You can run this command manually, but it is easiest just to have it run automatically.

## Adding new code

* Follow the style guidelines (below).
* Always write unit tests for any new code you add, and update tests for any code you modify.
  * Unit tests should assert, and not print to the console
  * WDL test files belong in the top directory `test`
* Submit a pull request when you are ready to have your code reviewed.

### Style guidelines

* We use [scalafmt style](https://scalameta.org/scalafmt/) with a few modifications. You don't need to worry so much about code style since you will use the automatic formatter on your code before it is committed.
* Readability is more important than efficiency or concision - write more/slower code if it makes the code more readable.
* Avoid using more complex features, e.g. reflection.

## Using sbt

sbt is the build system for Scala (similar to a Makefile). The following are the main commands you will use while developing.

### Compiling the code

Scala (like Java) is a compiled language. To compile, run:

```
$ sbt compile
```

If there are errors in your code, the compiler will fail with (hopefully useful) error messages.

### Running unit tests

You should always run the unit tests after every successful compile. Generally, you want to run `sbt testQuick`, which only runs the tests that failed previously, as well as the tests for any code you've modified since the last time you ran the tests. However, the first time you checkout the code (to make sure your development environment is set up correctly) and then right before you push any changes to the repository, you should run the full test suite using `sbt test`.

### Generating a stand-alone JAR file

```
$ sbt assembly
$ java -jar target/scala-2.13/wdlTools.jar ...
```

### Other sbt tips

#### Cache

sbt keeps the cache of downloaded jar files in `${HOME}/.ivy2/cache`. For example, the WDL jar files are under `${HOME}/.ivy2/cache/org.broadinstitute`. In case of problems with cached jars, you can remove this directory recursively. This will make WDL download all dependencies (again).

## wdlTools CLI

### Adding a new command

1. Create a new class in a file with the same name as the command:
    ```scala
    package wdlTools.cli
   
    import scala.language.reflectiveCalls
    
    case class MyCommand(conf: WdlToolsConf) extends Command {
      override def apply(): Unit = {
          ...
      }
    }
    ```
2. In `package`, add a new subcommand definition:
    ```scala
    val mycommand = new WdlToolsSubcommand("mycommand", "description") {
        // add options, for example
        val outputDir: ScallopOption[Path] = opt[Path](
            descr = "Directory in which to output files",
            short = 'O'
        )
    }
    ```
3. In `Main`, add your command to the pattern matcher:
    ```scala
    conf.subcommand match {
       case None => conf.printHelp()
       case Some(subcommand) =>
         val command: Command = subcommand match {
           case conf.mycommand => MyCommand(conf)
           ...
           case other          => throw new Exception(s"Unrecognized command $other")
         }
         command.apply()
    }
    ```

## Releasing a new version

### Release check list

- Make sure regression tests pass
- Update release notes and README.md
- Make sure the version number in `src/main/resources/application.conf` is correct. It is used when building the release.
- Merge onto master branch, and make sure CI tests pass
- Tag release with new version:
```
git tag $version
git push origin $version
```
- Update [releases](https://github.com/dnanexus-rnd/wdlTools/releases) github page, use the `Draft a new release` button, and upload a wdlTools.jar file.

### Post release

- Update the version number in `src/main/resources/application.conf`. We don't want to mix the experimental release, with the old code.