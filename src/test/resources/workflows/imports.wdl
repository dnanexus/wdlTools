version 1.0

import "I.wdl" as biz

workflow foo {
  call biz { input : s = "anybody there?" }
}
