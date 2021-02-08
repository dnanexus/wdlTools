version 1.0

struct Name {
  String first
  String last
}

task foo {
  input {
    Array[File] tranches
  }
  Int n = 4
  String sample_id = "hello"

  command {}

  output {
    String a = "a"
    String ab = a + "b"
    Int m = n + 3
    Array[String] quality_scores = read_lines("${sample_id}.scores.txt")
    File tranches_out = "${sample_id}"
    Name p = object {
      first: "Joe",
      last: "Blow"
    }
  }
}
