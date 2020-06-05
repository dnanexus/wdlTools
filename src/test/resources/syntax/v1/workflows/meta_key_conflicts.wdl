version 1.0

task inc {
  input {
    Int i
  }

  meta {
    version: "1.1"
  }

  parameter_meta {
    i: {
      description: "An int",
      default: 3,
      array_of_objs: [
        {
          foo: false,
          bar: 1.0
        }
      ]
    }
  }

  command <<<
    python -c "print(~{i} + 1)"
  >>>

  output {
    Int result = read_int(stdout())
  }
}

task mul {
  command <<<
  >>>
}
