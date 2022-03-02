version 1.0

# `b` is never referenced, and thus should not appear in the expr graph
# `extra_int` is referenced by an unused private var "extra_var", yet it is still evaluated and added to the expr graph
task foo {
  input {
    File f
    String s = basename(f, ".txt")
    String? name
    Int? i
    Float? d
    Boolean? b
    Int? extra_int
  }

  Int x = select_first([i, 10])
  String y = "${x}"

  command <<<
  ~{default="joe" name}
  >>>

  runtime {
    docker: "ubuntu"
    memory: "${x}GB"
  }

  Float dout = select_first([d, 1.0])

  Int? extra_var = extra_int

  output {
    String sout = s
    Float dout2 = dout
  }
}
