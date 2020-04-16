version 1.0

workflow foo {
  # convert an optional file-array to a file-array
  Array[File]? files = ["a", "b"]
  Array[File] files2 = files

  # Convert an optional int to an int
  # This will fail at runtime if a is null
  Int? a = 3
  Int b = a

#  Array[Int] x = [a]

#  output {
#    Array[Int] result = x
#  }
}
