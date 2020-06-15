version 1.0

workflow call_native_v1 {
  input {
    Int x = 0
    Boolean flag = true
  }

  call native_sum_012 as sum_var1

  if (flag) {
    call native_mk_list as mk_list2 {
      input: a=x, b=x
    }
  }

  output {
    Int sum_var1_result = sum_var1.result
    Array[Int]+? mk_list2_all = mk_list2.all
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

task native_mk_list {
  input {
    Int a
    Int b
  }

  command <<< >>>

  output {
    Array[Int]+ all = [0]
  }

  meta {
    type: "native"
    id: "applet-FqX3V780ffPKzBJ13xFg4pBb"
  }
}