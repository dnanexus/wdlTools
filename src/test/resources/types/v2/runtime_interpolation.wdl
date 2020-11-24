version development

task foo {
  input {
    Int default_disk_gb
  }
  command {}
  runtime {
      disks: "local-disk ${default_disk_gb}"
  }
}