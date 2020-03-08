version 1.0

import "I.wdl" as biz

#import "https://github.com/gatk-workflows/gatk4-germline-snps-indels/blob/master/JointGenotyping-terra.wdl" as terra

workflow foo {
  call biz { input : s = "anybody there?" }
}
