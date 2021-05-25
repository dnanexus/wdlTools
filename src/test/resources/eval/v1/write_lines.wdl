version 1.0

task write_lines_task {
  input {
    String x
    String y
  }

  command <<<
    cat ~{write_lines(["~{x} ~{y}"])}
  >>>

  output {
    String out = read_lines(stdout())[0]
  }

  runtime {
    docker: "ubuntu:20.04"
  }
}
