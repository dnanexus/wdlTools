version 1.1

task fix {
  input {
    Int x
  }

  String to_ph1 = "a${x}b"
  String to_ph2 = "${x}z"

  command <<< >>>
}