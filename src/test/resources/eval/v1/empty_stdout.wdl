version 1.0

task empty_stdout {
  command {}

  output {
    String result = read_string(stdout())
  }
}