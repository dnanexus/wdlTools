version 1.0

task simple {
  input {
    Int a
    Int b
    String s1
    String s2
  }

  String s3 = s1 + s2
  String s4 = s3 + 89

  command {
  }
  output {
    Int c = a + b
    Int d = a + 2
  }
}
