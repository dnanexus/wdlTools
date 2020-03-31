version 1.0

task foo {
  input {
    String s
    Int i
  }

  String x = "${s}.txt"
  String y = "foo"
  Int z = i + i
  Int a = if (i > 1) then 2 else 3

  command <<<
    echo ~{x}
    echo ~{i * i}
    echo ~{if true then "a" else "b"}
  >>>

  output {
    String sout = x
  }

  runtime {
    docker: "debian:stretch-slim"
  }
}