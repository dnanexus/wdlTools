version 1.0

workflow call_udf {
  input {
    String name
  }
  call call_say_hello_fn { input: name = name }
  call say_hello { input: name = name }
  output {
    String greeting1 = call_say_hello_fn.greeting
    String greeting2 = say_hello.greeting
  }
}

task call_say_hello_fn {
  input {
    String name
  }
  command <<<
    echo ~{say_hello(name)}
  >>>
  output {
    String greeting = read_string(stdout())
  }
}

task say_hello {
  input {
    String name
  }
  command {}
  output {
    String greeting = ""
  }
}