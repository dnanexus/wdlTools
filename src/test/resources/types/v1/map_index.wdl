version 1.0

task map_index {
  input {
    Pair[String, String] p
    Map[String, String] m
  }

  command {}

  output {
    String s = m[p.left]
  }

  runtime {
    docker: "ubuntu"
  }
}
