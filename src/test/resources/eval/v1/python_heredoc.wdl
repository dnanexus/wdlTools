version 1.0

task heredoc {
  File inp = "/path/to/file.txt"

  command<<<
  python <<CODE
    with open("~{inp}") as fp:
      for line in fp:
        if not line.startswith('#'):
          print(line.strip())
  CODE
  >>>
}
