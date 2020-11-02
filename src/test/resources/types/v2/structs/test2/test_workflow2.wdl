version development

import "test2/test_struct2.wdl"

workflow test_workflow2 {
  input {
    StructPlaceHolder wrkflw2_struct
  }

  call task2 {
    input:
      wrkflw2_struct = wrkflw2_struct
  }

  output {
    File workflow2_output_file = task2.workflow2_output_file
  }
}

task task2 {
  input {
    StructPlaceHolder wrkflw2_struct
  }

  command <<<
    set -uexo pipefail

    echo ~{wrkflw2_struct.field_place_holder} > tst2
  >>>

  runtime {
    container: "debian:stretch-slim"
  }

  output {
    File workflow2_output_file = "tst2"
  }
}