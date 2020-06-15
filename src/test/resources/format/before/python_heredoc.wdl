version 1.0

task RuntimeDockerChoice {
  input {
    String imageName
  }
  command <<<

    python <<CODE
    import os
    import sys
    print("We are inside a python docker image")
    CODE

  >>>

  output {
    String retval = read_string(stdout())
  }

  runtime {
    docker: imageName
    memory: "2 GB"
  }
}
