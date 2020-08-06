version 1.0

task comparison {
  input {
    Int i
    String s
  }

  # On first thought, you would assume this comparison fails since i and s are different types.
  # On second thought, you might think it should succeed under lenient type checking because
  #  Int and String are coercible to each other
  # On third thought, you will realize it will still fail because there are multiple valid
  #  comparisons that may return different results: `String(i) < s` vs `i < Int(s)`
  # On fourth thought, WDL should define order of precedence for coercions so the correct
  #  comparison to use can be resolved deterministically (and in this case, Int -> String
  #  should take precendence)
  Boolean ct_1 = i < s

  command {}
}
