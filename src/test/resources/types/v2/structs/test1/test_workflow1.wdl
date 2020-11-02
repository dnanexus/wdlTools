version development

import "test1/test_struct1.wdl"

workflow test_workflow1 {
  input {
    StructPlaceHolder wrkflw1_struct
  }

  call task1 {
    input:
      wrkflw1_struct = wrkflw1_struct
  }

  output {
    File workflow1_output_file = task1.workflow1_output_file
  }
}

task task1 {
  input {
    StructPlaceHolder wrkflw1_struct
  }

  command <<<
    set -uexo pipefail

    echo ~{wrkflw1_struct.field_place_holder} > tst1
  >>>

  runtime {
    container: "debian:stretch-slim"
  }

  output {
    File workflow1_output_file = "tst1"
  }
}
