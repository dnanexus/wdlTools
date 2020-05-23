version 1.0

task foo {
  input {
    String s
  }

  command <<<
  echo ~{s}
  >>>

  output {
    String t = s
  }

  parameter_meta {
    s: "A string input"
    t: "A string output"
    u: "A non-existant parameter"
  }
}