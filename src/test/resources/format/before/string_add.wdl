version 1.0

task string_add {
  input {
    Int memMb
  }
  command <<<>>>
  runtime {
    memory: memMb + " MiB"
  }
}