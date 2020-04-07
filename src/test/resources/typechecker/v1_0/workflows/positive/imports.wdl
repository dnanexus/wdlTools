version 1.0

import "library.wdl" as lib

workflow w {

  # calls to imports
  call lib.concat as concat { input : a = "He prefers yellow", b = " to green" }
  call lib.add { input : a = 3, b = 5 }
  call lib.gen_array { input : len = 10 }

  # access results
  String longStr = concat.result
  Int i = add.result
  Array[Int] a = gen_array.result
}
