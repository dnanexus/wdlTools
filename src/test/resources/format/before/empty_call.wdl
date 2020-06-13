version 1.0

workflow call_native_v1 {
    call native_sum_012 as sum_var1

    output {
        Int sum_var1_result = sum_var1.result
    }
}

task native_sum_012 {
  input {
    Int a
    Int b
  }

  command <<< >>>

  output {
    Int result = 0
  }

  meta {
    type: "native"
    id: "applet-FqX31jj0ffP7V4b23x3qv2X9"
  }
}