version 1.0

task spaces {
  input {
    String s
  }

  String x = sub (s, "x", "y")

  command <<<
    echo ~{sub (x, "a", "b")}
  >>>

  output {
    String out = read_string(stdout())
  }
}
