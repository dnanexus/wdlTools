version 1.0

task nonstandard {
  input {
    Int disk_size_gb = 100
  }

  command <<<
    echo hi
  >>>

  output {}

  runtime {
    disks: "local-disk ${disk_size_gb} SSD"
  }
}