# A task that counts how many lines a file has
version 1.0

task wc {
  input {
    File inp_file
  }
  # Just a random declaration
  Int i = 4 + 5

  output {
    # Int num_lines = read_int(stdout())
    Int num_lines = 3
  }
  command {
    wc -l ~{inp_file}
  }
  meta {
    author : "Robin Hood"
  }
  parameter_meta {
    inp_file : "just because"
  }
}
