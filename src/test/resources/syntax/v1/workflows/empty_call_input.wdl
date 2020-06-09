version 1.0

workflow test {
  call foo {}
  call foo {
    input:
  }
}

task foo {
  input {
    String? s
  }

  command {}
}