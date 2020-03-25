version 1.0

task simple {
  input {
    String a
    String b
  }
  output {
    Int c = a + b
  }
}
