version 1.0

# should succeed type-checking because the scatter array
# is statically provably non-empty
workflow nonempty_array_coercion {
  scatter (j in [1, 2]) {
    Int k = j + 1
  }
  output {
    Array[Int]+ out = k
  }
}