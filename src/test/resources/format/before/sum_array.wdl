version 1.0

task SumArray {
  input {
    Array[Int] numbers
  }

  command <<<
    python3 -c "print(~{sep=" + " numbers})"
  >>>

  output {
    Int result = read_int(stdout())
  }
}