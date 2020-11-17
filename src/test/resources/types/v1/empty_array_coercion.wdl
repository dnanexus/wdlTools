version 1.0

# i can be 0, so it is possible for k to be an empty array;
# however, the WDL spec says that nonEmpty is only a runtime
# check, so it should be allowed to pass static type checking
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