version 1.0

import "subworkflows/sub.wdl"
import "subworkflows/tasks.wdl"


workflow top_level {
  input {
    Float z
    String s
    Int i
  }

  call sub.sub {
    input:
      s = s,
      i = i,
      z = z
  }
  call tasks.foo {
    input:
      s = s,
      i = i,
      z = z
  }

  output {
    Output out = foo.out
  }
}