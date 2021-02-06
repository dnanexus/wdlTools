version 1.1

workflow functions {
  Int a = min(1, 2)
  Int b = max(1, 2)
  Float c = min(1, 2.0)
  Float d = max(1.0, 2)
  Float e = min(1.0, 2.0)

  Array[Pair[Int, Int]] p = [(1,2), (3,4)]
  Pair[Array[Int], Array[Int]] punzip = unzip(p)
  Array[Pair[Int, Int]] pzip = zip(punzip.left, punzip.right)

  Map[String, Int] m = {
    "a": 1,
    "b": 2
  }
  Array[Pair[String, Int]] mpairs = as_pairs(m)
  Map[String, Int] mmap = as_map(mpairs)
  Array[String] mkeys = keys(m)

  Array[Pair[String, Int]] arr = [("a", 1), ("a", 2), ("b", 3)]
  Map[String, Array[Int]] acollect = collect_by_key(arr)

  Array[String] suf = suffix(" world", ["hello", "goodbye"])
  Array[String] dquoted = quote([1, 2])
  Array[String] squoted = squote([true, false])
  String sepd = sep(",", [1.0, 2.0])
}