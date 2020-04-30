version 1.0

workflow foo {
  Float x = 1.4
  Int n1 = floor(x)
  Int n2 = ceil(x)
  Int n3 = round(x)

  Array[String] cities = ["LA", "Seattle", "San Francisco"]
  File flc = write_lines(cities)
  Array[String] cities2 = read_lines(flc)

  Array[Array[String]] table = [
  ["A", "allow"],
  ["B", "big"],
  ["C", "clam"]]

  File flTbl = write_tsv(table)
  Array[Array[String]] table2 = read_tsv(flTbl)

  Map[String, String] m = {"name" : "hawk", "kind" : "bird" }
  File flm = write_map(m)
  Map[String, String] m2 = read_map(flm)

  # sub
  String sentence = "He visited three places on his trip: Aa, Ab, C, D, and E"
  String sentence1 = sub(sentence, "He", "She")
  String sentence2 = sub(sentence, "A[ab]", "Berlin")
  String sentence3 = sub(sentence, "[a-z]*", "")

  # range
  Array[Int] ar3 = range(3)

  # transpose
  Array[Array[Int]] ar_ar = [[1, 2, 3], [4, 5, 6]]
  Array[Array[Int]] ar_ar2 = transpose(ar_ar)

  # zip
  Array[String] letters = ["A", "B", "C"]
  Array[Boolean] flags = [true, false, true]
  Array[Pair[String, Boolean]] zlf = zip(letters, flags)

  # cross
  Array[Int] numbers = [1, 13]
  Array[Pair[String, Int]] cln = cross(letters, numbers)
}
