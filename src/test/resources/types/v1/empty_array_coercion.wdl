version 1.0

# should fail to type-check because i can be 0,
# resulting in an empty array
workflow empty_array_coercion {
  input {
    Int i
  }
  scatter (j in range(i)) {
    Int k = j + 1
  }
  output {
    Array[Int]+ out = k
  }
}