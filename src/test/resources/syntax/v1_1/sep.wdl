version 1.1

task sep_test {
  input {
    Array[String] strings
  }

  command {}

  output {
    String s1 = "${sep="," strings}"
    String s2 = sep(strings, ",")
  }
}