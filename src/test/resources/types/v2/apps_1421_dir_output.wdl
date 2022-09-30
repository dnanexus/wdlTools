version development

task foldertest {
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

workflow folderrun {
  input {
    Directory WorkingDir
    String sid
  }

  call foldertest {
    input: 
      WorkingDir=WorkingDir, sid=sid
  }
}
