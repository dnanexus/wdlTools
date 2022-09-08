version development

struct Index {
    Directory? index_dir
    String? prefix
}

workflow apps_1353_v_dir_coercion {
  input {
    Index idx
  }
  call apps_1353_task {
    input:
      WorkingDir = idx.index_dir,
      outname = idx.prefix
  }
  output {}
}

task apps_1353_task {
  input {
    Directory? WorkingDir
    String? outname
  }
  command {
    set -euxo pipefail
    ls -l ~{WorkingDir}
    echo ~{outname} > outfile.txt
  }
  output {}
}
