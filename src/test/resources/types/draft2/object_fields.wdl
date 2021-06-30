task mk_object {
  command {
    echo -e "a\tb\tc"
    echo -e "1\t2\t3"
  }
  output {
    Object out = read_object(stdout())
  }
  runtime {
    docker: "ubuntu:latest"
  }
}

task use_object {
  Object obj_in
  command {
    echo ${obj_in.a}
    echo ${obj_in.b}
    echo ${obj_in.c}
  }
  output {
    Array[String] lines = read_lines(stdout())
  }
  runtime {
    docker: "ubuntu:latest"
  }
}

workflow object_access {
  call mk_object
  call use_object { input: obj_in = mk_object.out }
  if (mk_object.out.a == "1") {
    String greet = "hello"
  }
  output {
    Array[String] lines = use_object.lines
    String? greeting = greet
  }
}
