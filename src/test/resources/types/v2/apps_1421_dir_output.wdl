version development

task apps_1421_task_01 {
  input {
    String sid
    Directory WorkingDir
  }

  command {
    set -euxo pipefail
    ls -l ~{WorkingDir}
    mkdir folderoutput
    mv ~{WorkingDir}/* folderoutput/
  }
  output {
    Directory outdir = "folderoutput/"
  }
}

workflow apps_1421_dir_output {
  input {
    Directory WorkingDir
    String sid
  }

  call apps_1421_task_01 {
    input: 
      WorkingDir=WorkingDir, sid=sid
  }
}
