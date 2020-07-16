version 1.0

task test {
  input {
    String s = "hello"
  }

  command <<<
  echo ~{s} > hello.txt
  >>>

  output {
    String x = "${s}"
    File f = "hello.txt"
  }

  runtime {
    docker: "ubuntu"
  }
}