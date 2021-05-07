version 1.1

task string_tab {
  Array[String] a = ["1\t1","2\\t2","3\\\t3","4\\\\t4"]

  command <<<
    cat -vet ~{write_lines(a)}
  >>>

  output {
    String out = read_string(stdout())
  }
}