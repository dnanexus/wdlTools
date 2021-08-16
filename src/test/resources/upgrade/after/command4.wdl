version 1.0

task cat {
  input {
    String s
  }

  command <<<
    echo ~{s}
  >>>
}