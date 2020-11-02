version development

import "test1/test_struct1.wdl" as test_struct1 alias StructPlaceHolder as StructPlaceHolderNew1
import "test2/test_struct2.wdl" as test_struct2 alias StructPlaceHolder as StructPlaceHolderNew2
import "test1/test_workflow1.wdl"
import "test2/test_workflow2.wdl"

workflow parent_workflow {
  input {
    StructPlaceHolderNew1 wrkflw_parent1_struct
    StructPlaceHolderNew2 wrkflw_parent2_struct
  }

  call test_workflow1.test_workflow1 {
    input:
      wrkflw1_struct = wrkflw_parent1_struct
  }

  call test_workflow2.test_workflow2 {
    input:
      wrkflw2_struct = wrkflw_parent2_struct
  }

  output {
    Array [File] parent_workflow_output = [test_workflow1.workflow1_output_file, test_workflow2.workflow2_output_file]
  }
}