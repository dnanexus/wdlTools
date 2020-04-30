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
}
