version 1.0

# `b` is never referenced, and thus should not appear in the expr graph
task foo {
  input {
    File f
    String s = basename(f, ".txt")
    String? name
    Int? i
    Float? d
    Boolean? b
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

  output {
    String sout = s
    Float dout2 = dout
  }
}
