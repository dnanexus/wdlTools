# A task that counts how many lines a file has
task wc {
  File inp_file
  # Just a random declaration
  # with a multi-line comment
  Int i = 4 + 5

  output {
    # Int num_lines = read_int(stdout())
    Int num_lines = 3
  }

  command {
    wc -l ${inp_file}
  }

  meta {
    ## This is a pre-formatted
    ## comment
    # mixed with a regular comment
    #
    # that has an empty line
    author : "Robin Hood"
  }

  parameter_meta {
    inp_file : "just because"
  }
}
