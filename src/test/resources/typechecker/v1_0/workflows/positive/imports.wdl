version 1.0

import "library.wdl" as lib

workflow w {
  call lib.concat as concat { input : a = "He prefers yellow", b = " to green" }
}
