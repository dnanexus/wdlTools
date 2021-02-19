version 1.0

task danglilng {
  input {
    String a = "a"
    String? b
  }

  command <<<
  echo ~{a} \
    ~{b}
  echo "hello"
  >>>

  output {
    String result = read_string(stdout())
  }
}
