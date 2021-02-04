version 1.0

task bug_382 {
  input {
    String scripts
  }

  command <<<
    ~{scripts}
  >>>
}