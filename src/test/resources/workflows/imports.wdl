version 1.0

import "I.wdl" as biz

# try an http address
#import "https://github.com/dnanexus-rnd/wdlTools/blob/master/src/test/resources/tasks/wc.wdl" as wc

workflow foo {
  call biz { input : s = "anybody there?" }
  call wc
}
