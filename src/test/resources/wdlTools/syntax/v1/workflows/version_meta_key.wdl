version 1.0

task inc {
  input {
    Int i
  }

  meta {
    version: "1.0"
  }

  command <<<
    python -c "print(~{i} + 1)"
  >>>

  output {
    Int result = read_int(stdout())
  }
}