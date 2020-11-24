version development

struct Foo {
  Int cpu
}

task bar {
  input {
    Foo foo
  }
  command {}
  runtime {
    cpu: foo.cpu
  }
}