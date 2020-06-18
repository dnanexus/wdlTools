version 1.0

task echo_hi {
  command <<< echo 'hi' >>>

  output {
    String results = read_string(stdout())
  }
}

task echo_greeting {
  input {
    String greeting
  }

  command <<< echo '~{greeting}' >>>

  output {
    String results = read_string(stdout())
  }
}