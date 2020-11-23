version development
workflow parameter_meta_comment1 {
  input {
    File p
  }
  output {
    File out = p
  }
  parameter_meta {
    #foo
    p: "p"
  }
}