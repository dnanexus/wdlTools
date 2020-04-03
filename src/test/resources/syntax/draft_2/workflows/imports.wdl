# a local file
import "I.wdl" as biz

# an http address
import "https://raw.githubusercontent.com/dnanexus-rnd/wdlTools/master/src/test/resources/syntax/tasks/wc.wdl" as wc

workflow foo {
  call biz { input : s = "anybody there?" }
  call wc
}
