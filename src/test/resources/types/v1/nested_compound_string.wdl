version 1.0

task nested_compound_string {
  input {
    String? name
  }

  command <<<
  say_hi ~{if defined(name) then "--name ~{name}" else ""}
  >>>

  output {
    String greeting = read_string(stdout())
  }

  runtime {
    docker: "ubuntu"
  }
}