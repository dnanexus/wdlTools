version 1.0

### This is a top-level doc comment

import "foo.wdl" as bar alias Baz as Blorf

struct Bar {
  String s
}

### This is a comment on the Foo struct
struct Foo {
  ### This is a comment on Foo.i
  Int i
  Bar b
}

workflow simple {
  input {
    String s
  }

  call foo {
    input:
      s = s,
      f = {
        "i": 1,
        "b": {
          "s": "hello"
        }
      }
  }

  output {
    String ss = foo.ss
  }

  meta {
    description: "simple workflow"
  }
}

### This is a comment on foo
task foo {
  input {
    ### Input foo.s
    String s = "hello"
    ### Input foo.f
    Foo f
  }

  ### This doesn't document anything and so is ignored even though it starts with '###'

  command <<<
  >>>

  output {
    String ss = s
  }

  runtime {
    ### The docker image
    docker: "debian"
    memory: "2 GB"
  }

  meta {
    description: "This is a description of the task"
  }

  parameter_meta {
    s: {
      description: "A string",
      choices: ["foo", "bar"]
    }
  }
}