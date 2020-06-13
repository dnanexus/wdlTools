version 1.0

workflow foo {
  input {
    String s
  }

  call bar {
    input:
      s = s
  }

  output {
    String o = bar.o
  }
}

task bar {
  input {
    String s
  }

  command <<< >>>

  output {
    String o = s
  }

  runtime {
    docker: "ubuntu"
  }
}