version 1.0

task echo_line_split {
  command <<<
    echo 1 hello world | sed 's/world/wdl/'
    echo 2 hello \
    world \
    | sed 's/world/wdl/'
    echo 3 hello \
    world | \
    sed 's/world/wdl/'
  >>>

  output {
    String results = read_string(stdout())
  }
}
