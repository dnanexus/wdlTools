version 1.0

workflow biz {
  input {
    String s
  }

  call bar as boz {
    input: i = s
  }
}
