version 1.0

workflow nested_placeholders {
  input {
    Array[String]? s
  }

  output {
    String out = "~{if defined(s) then "~{sep="," select_first([s])}" else "null"}"
  }
}