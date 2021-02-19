version 1.1

workflow read_lines_test {
  input {
    File f
  }

  Array[Int] i = read_lines(f)
}