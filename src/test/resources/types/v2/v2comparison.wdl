version development

task comparison {
  input {
    Boolean b1
    Boolean b2
  }

  # even though Int is coercible to String, String comparison
  # is disallowed starting in 2.0
  Boolean ct_1 = b1 < b2

  command {}
}
