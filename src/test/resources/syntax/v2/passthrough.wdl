version development

task foo {
  input {
    String s
  }
  command {}
  output {
    String out = s
  }
}

workflow passthrough {
  input {
    String s
  }
  call foo { input: s }
  output {
    String out = foo.out
  }
}