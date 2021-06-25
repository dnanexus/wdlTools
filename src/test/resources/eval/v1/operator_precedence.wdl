version 1.0

workflow operator_precedence {
  input {
    Int x
    Int y
  }

  Boolean b = x > -2 && y > 0

  if (b) {
    Boolean z = true
  }

  output {
    Boolean zout = select_first([z, false])
  }
}