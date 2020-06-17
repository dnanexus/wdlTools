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

task Colocation {
  input {
    File A
    File B
  }
  command <<<
python <<CODE
import os
dir_path_A = os.path.dirname("/home/dnanexus/inputs/reallyreallyreallyreallyreallyreallyreallyreallyreallyreallyreallylongfilename")
dir_path_B = os.path.dirname("/home/dnanexus/inputs/1/reallyreallyreallyreallyreallyreallyreallyreallyreallyreallyreallylongfilename")
print((dir_path_A == dir_path_B))
CODE
  >>>
  output {
    String result = read_string(stdout())
  }
}
