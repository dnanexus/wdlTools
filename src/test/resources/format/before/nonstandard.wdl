version 1.0

task nonstandard {
  input {
    Int disk_size_gb = 100
  }

  String twoXsize = disk_size_gb * 2

  command <<<
  echo ~{twoXsize}
  >>>

  output {}

  runtime {
    disks: "local-disk " + disk_size_gb + " SSD"
  }
}
