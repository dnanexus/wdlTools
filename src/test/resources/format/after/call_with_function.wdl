version 1.0

workflow call_with_function {
  input {
    String? bork
  }

  call foo {
    input:
      x = 1,
      y = flatten(
        [["long", "long", "long", "long", "long", "long", "long", "long", "long"], ["goodbye"]]
      ),
      z = 2
  }

  output {
    String out = foo.out
  }
}

task foo {
  input {
    Int x
    Array[String] y
    Int z
  }

  command <<< >>>

  output {
    String out = "bar"
  }
}
