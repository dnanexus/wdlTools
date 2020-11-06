version 1.0

import "tasks.wdl"

workflow sub {
  input {
    Int i
    String s
    Float z
  }

  call tasks.foo {
    input:
      z = z,
      s = s,
      i = i
  }

  output {
    Output out = foo.out
  }
}
