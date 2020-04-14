version 1.0

workflow foo {
  input {
    String? emptyString
  }

  Int l = length([1, 2, 3])
  String s = select_first([emptyString, "hello"])
  Array[Pair[Int, String]] z = zip([1, 4, 6], ["h", "a", "b"])
}
