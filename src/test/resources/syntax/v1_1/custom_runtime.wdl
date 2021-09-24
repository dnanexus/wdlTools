version 1.1
task dyno_runtime {
  input {
    Int i
  }
  command <<<
    echo "~{i}" > ~{i}
  >>>
  output {
    File dyno_out="${i}"
  }
  runtime {
    dx_timeout: "48H"
    dx_instance_type: "mem1_ssd1_v2_x2"
    dx_restart: object { max: 1, default: 2 }
  }
}