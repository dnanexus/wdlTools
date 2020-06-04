version 1.0

workflow conditionals_base {
  input {
    Int? i2
  }

  # Artifically construct an array of optional integers
  Int? i1 = 1
  Int? i3 = 100

  Array[Int?] powers10 = [i1, i2, i3]

}
