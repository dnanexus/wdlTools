version 1.1

import "structs.wdl"

workflow test_workflow {
  input {
    MyStructDB database
    String prefix
  }

  call test_task {
      input:
        prefix = prefix,
        database = database
    }
  
  output {
    File final_output = test_task.task_output
  }
}

task test_task {
  input {
    String prefix
    MyStructDB database
  }

  command <<<
    echo ~{database.num_columns}
    echo ~{database.num_rows}
    echo ~{prefix} > output.txt
  >>>

  runtime {
    docker: "debian:stretch-slim"
  }

  output {
    File task_output = "output.txt"
  }
}
