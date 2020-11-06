version 1.0

struct Output {
  String s
  Int i
  Float z
  String k
  Float f
  Int x
}

task foo {
  input {
    Int i
    String s
    Float z
  }

  command {}

  output {
    Output out = object {
      i: i,
      s: s,
      z: z,
      k: s,
      f: z,
      x: i
    }
  }
}
